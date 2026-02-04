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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 5.2: CHAIN LINK v3 - LATENCY FIXED
 * Prevents Packet Storms and Infinite Echo Loops.
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
    private val sendQueue = LinkedBlockingQueue<LinkPacket>(20) // Smaller queue for lower latency
    
    private val packetSequence = AtomicLong(0)

    private var isLeader = false
    private var groupOwnerAddress: InetAddress? = null
    
    // UNIQUE ID: Crucial for Echo Protection
    private val myDeviceId = Build.MODEL.take(8) + "_" + UUID.randomUUID().toString().takeLast(4)
    private val myGroupId = "GROUP_1"

    private var onPeersDiscovered: ((List<WifiP2pDevice>) -> Unit)? = null
    private var audioCallback: ((Long, ByteArray) -> Unit)? = null
    private var statusCallback: ((MeshStatus) -> Unit)? = null

    enum class MeshStatus { IDLE, DISCOVERING, ELECTING, LEADER, MEMBER, FAILED }

    fun setCallbacks(discovery: (List<WifiP2pDevice>) -> Unit, audio: (Long, ByteArray) -> Unit, status: (MeshStatus) -> Unit) {
        this.onPeersDiscovered = discovery; this.audioCallback = audio; this.statusCallback = status
    }

    fun startMesh(auto: Boolean) { stopMesh(); statusCallback?.invoke(MeshStatus.DISCOVERING); wifiP2pManager?.discoverPeers(channel, null) }

    fun createGroup() { wifiP2pManager?.createGroup(channel, null) }

    fun connectToRider(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress; groupOwnerIntent = 0 }
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
                udpSocket = DatagramSocket(UDP_PORT).apply { broadcast = true; reuseAddress = true }
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isRunning.get()) {
                    udpSocket?.receive(packet)
                    if (packet.length < LinkPacket.PACKET_HEADER_SIZE) continue
                    
                    val received = LinkPacket.fromByteArray(packet.data.copyOf(packet.length))
                    
                    // ECHO PROTECTION: Only process if NOT from me
                    if (received.originId != myDeviceId) {
                        audioCallback?.invoke(received.sequence, received.payload)
                        
                        // RELAY: Leaders repeat to party, but only if they haven't relayed it before
                        if (isLeader && received.hopCount == 0) {
                            relayPacket(received)
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Receiver stopped") }
        }.apply { name = "Mesh_Receiver"; start() }

        senderThread = Thread {
            try {
                val broadcastAddr = InetAddress.getByName("192.168.49.255")
                while (isRunning.get()) {
                    val linkPacket = sendQueue.take()
                    val data = linkPacket.toByteArray()
                    val target = if (isLeader) broadcastAddr else groupOwnerAddress
                    
                    target?.let {
                        udpSocket?.send(DatagramPacket(data, data.size, it, UDP_PORT))
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Sender stopped") }
        }.apply { name = "Mesh_Sender"; priority = Thread.MAX_PRIORITY; start() }
    }

    fun broadcastAudio(data: ByteArray) {
        if (!isRunning.get() || data.isEmpty()) return
        val packet = LinkPacket(myDeviceId, myGroupId, LinkPacket.LINK_CHAIN, 1, packetSequence.incrementAndGet(), 0, data)
        if (sendQueue.size >= 20) sendQueue.poll()
        sendQueue.offer(packet)
    }

    private fun relayPacket(packet: LinkPacket) {
        // Increment hop to prevent Leader from re-relaying its own broadcast
        val relay = packet.copy(hopCount = 1) 
        if (sendQueue.size >= 20) sendQueue.poll()
        sendQueue.offer(relay)
    }

    fun requestPeers() { wifiP2pManager?.requestPeers(channel) { peers -> onPeersDiscovered?.invoke(peers.deviceList.toList()) } }

    fun stopMesh() {
        isRunning.set(false)
        sendQueue.clear()
        Thread {
            try {
                udpSocket?.close()
                udpSocket = null
                wifiP2pManager?.removeGroup(channel, null)
            } catch (e: Exception) {}
            statusCallback?.invoke(MeshStatus.IDLE)
        }.start()
    }
}