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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * PHASE 5.2: HARDENED AUDIO ROUTER - HYBRID TRANSMISSION
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
    
    // Transmission States
    enum class TransmitMode { PTT, AUTO }
    private var currentMode = TransmitMode.PTT
    private var isPTTPressed = false
    private val VOX_THRESHOLD = 500 // Lowered for better sensitivity

    private var onPrimeStatusChanged: ((PrimeLinkManager.Status) -> Unit)? = null
    private var onMeshStatusChanged: ((ChainLinkManager.MeshStatus) -> Unit)? = null
    private var onChainDiscoveryChanged: ((List<android.net.wifi.p2p.WifiP2pDevice>) -> Unit)? = null

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }

    override fun onCreate() {
        super.onCreate()
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
        primeLinkManager.setCallbacks(
            audio = { seq, data -> 
                ensureHardwareReady()
                audioLoopback.playRemoteAudio(seq, data) 
            },
            status = { onPrimeStatusChanged?.invoke(it) }
        )

        chainLinkManager.setCallbacks(
            discovery = { onChainDiscoveryChanged?.invoke(it) },
            audio = { seq, data -> 
                ensureHardwareReady()
                audioLoopback.playRemoteAudio(seq, data) 
            },
            status = { status ->
                onMeshStatusChanged?.invoke(status)
                if (status == ChainLinkManager.MeshStatus.LEADER || status == ChainLinkManager.MeshStatus.MEMBER) {
                    ensureHardwareReady()
                }
            }
        )

        audioLoopback.setStreamingCallback { rawAudio ->
            val amplitude = audioLoopback.getPeakAmplitude()
            
            val shouldTransmit = when (currentMode) {
                TransmitMode.PTT -> isPTTPressed
                TransmitMode.AUTO -> amplitude > VOX_THRESHOLD
            }

            if (shouldTransmit) {
                primeLinkManager.sendAudio(rawAudio)
                chainLinkManager.broadcastAudio(rawAudio)
            }
        }
    }

    @Synchronized
    private fun ensureHardwareReady() {
        if (!audioLoopback.isActive()) {
            Log.d(TAG, "Starting Audio Core...")
            audioLoopback.startLoopback(enableLocalLoop = false)
        }
    }

    // --- MODE CONTROL ---
    fun setTransmitMode(mode: TransmitMode) {
        this.currentMode = mode
        if (mode == TransmitMode.AUTO) {
            ensureHardwareReady() // Mic must be on for VOX to work
        }
        Log.d(TAG, "Mode Switched to: $mode")
    }

    fun setPTTActive(active: Boolean) {
        this.isPTTPressed = active
        if (active) ensureHardwareReady()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Intercom Service Active"))
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

    fun startPrimeHost() = primeLinkManager.startServer()
    fun connectPrime(device: android.bluetooth.BluetoothDevice) = primeLinkManager.connectToDevice(device)

    fun stopAllComms() {
        Thread {
            primeLinkManager.stopAll()
            chainLinkManager.stopMesh()
            audioLoopback.stopLoopback()
        }.start()
    }

    fun setPrimeStatusCallback(cb: (PrimeLinkManager.Status) -> Unit) { this.onPrimeStatusChanged = cb }
    fun setMeshStatusCallback(cb: (ChainLinkManager.MeshStatus) -> Unit) { this.onMeshStatusChanged = cb }
    fun setChainDiscoveryCallback(cb: (List<android.net.wifi.p2p.WifiP2pDevice>) -> Unit) { this.onChainDiscoveryChanged = cb }
    fun getVolumeLevel(): Int = audioLoopback.getPeakAmplitude()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(chainLinkReceiver) } catch (e: Exception) {}
        stopAllComms()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "RideLink Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("RideLink Active").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()
}