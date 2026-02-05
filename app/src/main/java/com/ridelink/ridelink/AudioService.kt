package com.ridelink.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * PHASE 5.6 FINAL: CHAIN LINK CALLBACK WIRING FIX
 *
 * CRITICAL CHANGE: Properly wire memberCount callback
 * This was missing, causing Chain Link to never start audio capture
 */
class AudioService : Service() {

    private val TAG = "AudioService"
    private val binder = AudioServiceBinder()
    private val CHANNEL_ID = "RideLink_Intercom"
    private val NOTIFICATION_ID = 88

    private lateinit var audioLoopback: AudioLoopback
    private lateinit var primeLinkManager: PrimeLinkManager
    private lateinit var chainLinkManager: ChainLinkManager
    private var chainLinkReceiver: ChainLinkReceiver? = null

    private lateinit var audioManager: AudioManager

    enum class TransmitMode { PTT, AUTO }
    private var currentMode = TransmitMode.PTT
    private var isPTTPressed = false
    private val VOX_THRESHOLD = 500

    // CRITICAL: Peer tracking
    private var primePeerConnected = false
    private var chainMemberCount = 0
    private var isChainMeshActive = false // NEW: Track if we are part of a mesh regardless of count

    private var onPrimeStatusChanged: ((PrimeLinkManager.Status) -> Unit)? = null
    private var onMeshStatusChanged: ((ChainLinkManager.MeshStatus) -> Unit)? = null
    private var onChainDiscoveryChanged: ((List<android.net.wifi.p2p.WifiP2pDevice>) -> Unit)? = null

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "AudioService CREATED")
        Log.d(TAG, "═══════════════════════════════════════")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioLoopback = AudioLoopback(this)
        primeLinkManager = PrimeLinkManager(this)
        chainLinkManager = ChainLinkManager(this)

        setupWifiDirect()
        setupRouting()
        createNotificationChannel()
    }

    private fun setupWifiDirect() {
        val p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = p2pManager.initialize(this, mainLooper, null)
        chainLinkReceiver = ChainLinkReceiver(p2pManager, channel, chainLinkManager)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(chainLinkReceiver, filter)
    }

    private fun setupRouting() {
        // Prime Link callbacks
        primeLinkManager.setCallbacks(
            audio = { seq, data ->
                audioLoopback.playRemoteAudio(seq, data)
            },
            status = { status ->
                val wasConnected = primePeerConnected
                primePeerConnected = (status == PrimeLinkManager.Status.CONNECTED)

                Log.d(TAG, "[PRIME] Status: $status (peer connected: $primePeerConnected)")

                if (!wasConnected && primePeerConnected) {
                    Log.d(TAG, "[PRIME] ✓ First peer joined → Starting capture")
                    ensureHardwareReady()
                } else if (wasConnected && !primePeerConnected) {
                    Log.d(TAG, "[PRIME] ✗ Last peer left → Checking if should stop")
                    checkShouldStopCapture()
                }

                onPrimeStatusChanged?.invoke(status)
            }
        )

        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Wire ALL callbacks including memberCount
        // ═══════════════════════════════════════════════════════════════════
        chainLinkManager.setCallbacks(
            discovery = {
                Log.d(TAG, "[CHAIN] Discovered ${it.size} peers")
                onChainDiscoveryChanged?.invoke(it)
            },
            audio = { seq, data ->
                audioLoopback.playRemoteAudio(seq, data)
            },
            status = { status ->
                Log.d(TAG, "[CHAIN] Status: $status")
                
                // NEW: Trigger hardware start if we become LEADER or MEMBER
                val wasActive = isChainMeshActive
                isChainMeshActive = (status == ChainLinkManager.MeshStatus.LEADER || status == ChainLinkManager.MeshStatus.MEMBER)
                
                if (!wasActive && isChainMeshActive) {
                    Log.d(TAG, "[CHAIN] ✓ Mesh established ($status) → Starting engine bootstrap")
                    ensureHardwareReady()
                } else if (wasActive && !isChainMeshActive) {
                    Log.d(TAG, "[CHAIN] ✗ Mesh dissolved → Checking stop")
                    checkShouldStopCapture()
                }
                
                onMeshStatusChanged?.invoke(status)
            },
            memberCount = { count ->  // ← THIS WAS MISSING!
                val hadMembers = chainMemberCount > 0
                chainMemberCount = count

                Log.d(TAG, "[CHAIN] ✓✓✓ Member count: $count")

                if (!hadMembers && count > 0) {
                    Log.d(TAG, "[CHAIN] ✓ First member joined → Starting capture")
                    ensureHardwareReady()
                } else if (hadMembers && count == 0) {
                    Log.d(TAG, "[CHAIN] ✗ Last member left → Checking if should stop")
                    checkShouldStopCapture()
                }
            }
        )

        // Mic capture callback
        audioLoopback.setStreamingCallback { rawAudio ->
            val amplitude = audioLoopback.getPeakAmplitude()

            val shouldTransmit = when (currentMode) {
                TransmitMode.PTT -> isPTTPressed
                TransmitMode.AUTO -> amplitude > VOX_THRESHOLD
            }

            if (shouldTransmit) {
                if (primePeerConnected) {
                    primeLinkManager.sendAudio(rawAudio)
                }
                if (isChainMeshActive || chainMemberCount > 0) { // CHANGED: Allow transmission if mesh is active
                    chainLinkManager.broadcastAudio(rawAudio)
                }
            }
        }
    }

    @Synchronized
    private fun ensureHardwareReady() {
        // CHANGED: Also consider mesh activity state as a valid trigger
        val hasPeers = primePeerConnected || chainMemberCount > 0 || isChainMeshActive

        if (!hasPeers) {
            Log.w(TAG, "[HARDWARE] No peers connected, skipping capture start")
            return
        }

        if (!audioLoopback.isActive()) {
            Log.d(TAG, "[HARDWARE] ✓✓✓ Starting Audio Core (prime=$primePeerConnected, chain=$chainMemberCount, mesh=$isChainMeshActive)")
            audioLoopback.startLoopback(enableLocalLoop = false)
        } else {
            Log.d(TAG, "[HARDWARE] Already active")
        }
    }

    @Synchronized
    private fun checkShouldStopCapture() {
        // CHANGED: Check all possible triggers before stopping
        val hasPeers = primePeerConnected || chainMemberCount > 0 || isChainMeshActive

        if (!hasPeers && audioLoopback.isActive()) {
            Log.d(TAG, "[HARDWARE] ✓ No peers remaining → Stopping capture")
            audioLoopback.stopLoopback()
        } else if (hasPeers) {
            Log.d(TAG, "[HARDWARE] Still have peers (prime=$primePeerConnected, chain=$chainMemberCount, mesh=$isChainMeshActive)")
        }
    }

    fun setTransmitMode(mode: TransmitMode) {
        this.currentMode = mode
        Log.d(TAG, "[MODE] Switched to: $mode")
    }

    fun setPTTActive(active: Boolean) {
        this.isPTTPressed = active
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("RideLink Multi-Link Active"))
        return START_STICKY
    }

    fun startAutonomousMesh() = chainLinkManager.startMesh(auto = true)
    fun startManualDiscovery() = chainLinkManager.startMesh(auto = false)
    fun createParty() = chainLinkManager.createGroup()
    fun joinParty(device: android.net.wifi.p2p.WifiP2pDevice) = chainLinkManager.connectToRider(device)
    fun stopChainMesh() = chainLinkManager.stopMesh()

    fun resetEngine() {
        stopAllComms()
        android.os.Handler(mainLooper).postDelayed({ startManualDiscovery() }, 1500)
    }

    fun startPrimeHost() {
        Log.d(TAG, "[PRIME] Host mode activated (waiting for peer)")
        primeLinkManager.startServer()
    }

    fun connectPrime(device: android.bluetooth.BluetoothDevice) {
        Log.d(TAG, "[PRIME] Connecting to: ${device.name}")
        primeLinkManager.connectToDevice(device)
    }

    fun stopAllComms() {
        Log.d(TAG, "[STOP] Terminating all links")
        Thread {
            primeLinkManager.stopAll()
            chainLinkManager.stopMesh()
            audioLoopback.stopLoopback()
        }.start()
    }

    fun setPrimeStatusCallback(cb: (PrimeLinkManager.Status) -> Unit) {
        this.onPrimeStatusChanged = cb
    }

    fun setMeshStatusCallback(cb: (ChainLinkManager.MeshStatus) -> Unit) {
        this.onMeshStatusChanged = cb
    }

    fun setChainDiscoveryCallback(cb: (List<android.net.wifi.p2p.WifiP2pDevice>) -> Unit) {
        this.onChainDiscoveryChanged = cb
    }

    fun getVolumeLevel(): Int = audioLoopback.getPeakAmplitude()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "AudioService DESTROYED")
        Log.d(TAG, "═══════════════════════════════════════")
        try { unregisterReceiver(chainLinkReceiver) } catch (e: Exception) {}
        stopAllComms()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "RideLink Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("RideLink Active")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()
}