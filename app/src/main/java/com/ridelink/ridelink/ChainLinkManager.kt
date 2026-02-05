package com.ridelink.intercom

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 5.5 FINAL: CHAIN LINK WITH MEMBER TRACKING
 */
@SuppressLint("MissingPermission")
class ChainLinkManager(private val context: Context) {

    private val TAG = "ChainLink"
    private val UDP_PORT = 8889

    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private val channel: WifiP2pManager.Channel? by lazy {
        wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
    }

    private val isRunning = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null

    private var receiverThread: Thread? = null
    private var senderThread: Thread? = null
    private val sendQueue = LinkedBlockingQueue<LinkPacket>(20)

    private val packetSequence = AtomicLong(0)

    private var isLeader = false
    private var groupOwnerAddress: InetAddress? = null

    private val myDeviceId = Build.MODEL.take(8) + "_" + UUID.randomUUID().toString().takeLast(4)
    private val myGroupId = "GROUP_1"

    // CRITICAL: Member tracking
    private val activeMembers = ConcurrentHashMap<String, Long>() // deviceId -> lastSeenTime
    private val memberCount = AtomicInteger(0)
    private val MEMBER_TIMEOUT_MS = 5000L

    private var onPeersDiscovered: ((List<WifiP2pDevice>) -> Unit)? = null
    private var audioCallback: ((Long, ByteArray) -> Unit)? = null
    private var statusCallback: ((MeshStatus) -> Unit)? = null
    private var memberCountCallback: ((Int) -> Unit)? = null

    enum class MeshStatus { IDLE, DISCOVERING, ELECTING, LEADER, MEMBER, FAILED }

    fun setCallbacks(
        discovery: (List<WifiP2pDevice>) -> Unit,
        audio: (Long, ByteArray) -> Unit,
        status: (MeshStatus) -> Unit,
        memberCount: (Int) -> Unit = {}
    ) {
        this.onPeersDiscovered = discovery
        this.audioCallback = audio
        this.statusCallback = status
        this.memberCountCallback = memberCount
    }

    fun startMesh(auto: Boolean) {
        stopMesh()
        statusCallback?.invoke(MeshStatus.DISCOVERING)
        wifiP2pManager?.discoverPeers(channel, null)
    }

    fun createGroup() {
        wifiP2pManager?.createGroup(channel, null)
    }

    fun connectToRider(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        wifiP2pManager?.connect(channel, config, null)
    }

    fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed || isRunning.get()) return
        isRunning.set(true)
        isLeader = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress
        statusCallback?.invoke(if (isLeader) MeshStatus.LEADER else MeshStatus.MEMBER)
        startUdpEngine()
    }

    private fun startUdpEngine() {
        receiverThread = Thread {
            try {
                udpSocket = DatagramSocket(UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "[UDP] Receiver started on port $UDP_PORT")

                while (isRunning.get()) {
                    udpSocket?.receive(packet)
                    if (packet.length < LinkPacket.PACKET_HEADER_SIZE) continue

                    val received = LinkPacket.fromByteArray(packet.data.copyOf(packet.length))

                    // Update member tracking
                    if (received.originId != myDeviceId) {
                        val now = System.currentTimeMillis()
                        val wasNew = !activeMembers.containsKey(received.originId)
                        activeMembers[received.originId] = now

                        if (wasNew) {
                            updateMemberCount()
                            Log.d(TAG, "[MEMBER] New member joined: ${received.originId} (total: ${memberCount.get()})")
                        }
                    }

                    // ECHO PROTECTION
                    if (received.originId != myDeviceId) {
                        audioCallback?.invoke(received.sequence, received.payload)

                        // RELAY if leader
                        if (isLeader && received.hopCount == 0) {
                            relayPacket(received)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[UDP] Receiver stopped: ${e.message}")
            }
        }.apply {
            name = "Mesh_Receiver"
            start()
        }

        senderThread = Thread {
            try {
                val broadcastAddr = InetAddress.getByName("192.168.49.255")

                Log.d(TAG, "[UDP] Sender started")

                while (isRunning.get()) {
                    val linkPacket = sendQueue.take()
                    val data = linkPacket.toByteArray()
                    val target = if (isLeader) broadcastAddr else groupOwnerAddress

                    target?.let {
                        udpSocket?.send(DatagramPacket(data, data.size, it, UDP_PORT))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[UDP] Sender stopped: ${e.message}")
            }
        }.apply {
            name = "Mesh_Sender"
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Member timeout checker
        Thread {
            while (isRunning.get()) {
                Thread.sleep(1000)
                pruneInactiveMembers()
            }
        }.apply {
            name = "Mesh_MemberChecker"
            start()
        }
    }

    fun broadcastAudio(data: ByteArray) {
        if (!isRunning.get() || data.isEmpty()) return

        val NETWORK_CHUNK = 160 // 80 samples × 2 bytes = 160 bytes
        var offset = 0

        while (offset < data.size) {
            val remaining = data.size - offset
            val chunkSize = minOf(NETWORK_CHUNK, remaining)
            val chunk = data.copyOfRange(offset, offset + chunkSize)

            val packet = LinkPacket(
                myDeviceId, myGroupId, LinkPacket.LINK_CHAIN, 1,
                packetSequence.incrementAndGet(), 0, chunk
            )

            if (sendQueue.size >= 20) sendQueue.poll()
            sendQueue.offer(packet)

            offset += chunkSize
        }
    }

    private fun relayPacket(packet: LinkPacket) {
        val relay = packet.copy(hopCount = 1)
        if (sendQueue.size >= 20) sendQueue.poll()
        sendQueue.offer(relay)
    }

    private fun updateMemberCount() {
        val count = activeMembers.size
        memberCount.set(count)
        memberCountCallback?.invoke(count)
    }

    private fun pruneInactiveMembers() {
        val now = System.currentTimeMillis()
        val removed = mutableListOf<String>()

        activeMembers.entries.removeIf { (deviceId, lastSeen) ->
            val inactive = (now - lastSeen) > MEMBER_TIMEOUT_MS
            if (inactive) removed.add(deviceId)
            inactive
        }

        if (removed.isNotEmpty()) {
            updateMemberCount()
            Log.d(TAG, "[MEMBER] Pruned inactive: $removed (remaining: ${memberCount.get()})")
        }
    }

    fun requestPeers() {
        wifiP2pManager?.requestPeers(channel) { peers ->
            onPeersDiscovered?.invoke(peers.deviceList.toList())
        }
    }
    fun getMeshStatus(): MeshStatus {
        return when {
            !isRunning.get() -> MeshStatus.IDLE
            isLeader -> MeshStatus.LEADER
            else -> MeshStatus.MEMBER
        }
    }
    fun stopMesh() {
        isRunning.set(false)
        sendQueue.clear()
        activeMembers.clear()
        memberCount.set(0)

        Thread {
            try {
                udpSocket?.close()
                udpSocket = null
                wifiP2pManager?.removeGroup(channel, null)
            } catch (e: Exception) {}
            statusCallback?.invoke(MeshStatus.IDLE)
            memberCountCallback?.invoke(0)
        }.start()
    }
}