package com.ridelink.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * PHASE 5.2 FIXED: PRODUCTION-READY AUDIO CORE
 *
 * CRITICAL FIXES:
 * 1. Proper prefill logic before playback starts
 * 2. Blocking-based playback (no tight loops)
 * 3. Intelligent silence insertion for underruns
 * 4. Adaptive buffer sizing for Bluetooth vs speakers
 *
 * Engineering Notes:
 * - AudioTrack.write() is BLOCKING - use this for timing, not Thread.sleep()
 * - ReorderBuffer now handles packet ordering, this just plays them
 * - Sequence numbers enable out-of-order packet recovery
 */
class AudioLoopback(private val context: Context) {

    private val TAG = "AudioLoopback"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var sampleRate = 16000
    private val channelInConfig = AudioFormat.CHANNEL_IN_MONO
    private val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    // REORDER BUFFER: Now with proper adaptive logic
    private val reorderBuffer = ReorderBuffer(maxDelayMs = 100)

    @Volatile private var latestPeakAmplitude: Int = 0
    @Volatile private var underrunCount = 0
    private val hardwareLock = Any()

    // === FIX: CHUNK_SIZE should match network packet size ===
    // 160 samples × 2 bytes = 320 bytes per packet
    private val CHUNK_SIZE = 160
    private val captureBuffer = ShortArray(CHUNK_SIZE)
    private val playbackBuffer = ShortArray(CHUNK_SIZE)

    private var onAudioDataCaptured: ((ByteArray) -> Unit)? = null

    // Hardware Effect References
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    fun setStreamingCallback(callback: (ByteArray) -> Unit) {
        this.onAudioDataCaptured = callback
    }

    fun getPeakAmplitude(): Int = latestPeakAmplitude

    private fun isBluetoothConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    private fun routeToBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            val btDevice = commDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            btDevice?.let {
                audioManager.setCommunicationDevice(it)
                Log.d(TAG, "[BT] Routed to: ${it.productName}")
            }
        } else {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(TAG, "[BT] SCO started (legacy)")
        }
    }

    /**
     * Receive network packet with sequence number for reordering
     */
    fun playRemoteAudio(sequence: Long, data: ByteArray) {
        if (data.isEmpty() || data.size % 2 != 0) return

        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        reorderBuffer.insert(sequence, shorts)
    }

    fun startLoopback(enableLocalLoop: Boolean = false): Boolean {
        if (isRunning.get()) return false

        isRunning.set(true)
        reorderBuffer.clear()
        underrunCount = 0

        captureThread = Thread {
            runCaptureEngine(enableLocalLoop)
        }.apply {
            name = "AudioCore_Capture"
            priority = Thread.MAX_PRIORITY
            start()
        }

        playbackThread = Thread {
            runPlaybackEngine()
        }.apply {
            name = "AudioCore_Playback"
            priority = Thread.MAX_PRIORITY
            start()
        }

        return true
    }

    private fun runCaptureEngine(enableLocalLoop: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (isBluetoothConnected()) {
                routeToBluetoothSco()
                Thread.sleep(1000) // Wait for BT link establishment
            }

            synchronized(hardwareLock) {
                if (!initAudioHardware(sampleRate)) {
                    Log.w(TAG, "[INIT] 16kHz failed, falling back to 8kHz")
                    sampleRate = 8000
                    if (!initAudioHardware(sampleRate)) {
                        Log.e(TAG, "[INIT] Hardware initialization failed")
                        isRunning.set(false)
                        return
                    }
                }
                attachAudioEffects()
                audioRecord?.startRecording()
                audioTrack?.play()
            }

            Log.d(TAG, "[CAPTURE] Started at ${sampleRate}Hz, chunk=$CHUNK_SIZE samples")

            while (isRunning.get()) {
                val read = audioRecord?.read(captureBuffer, 0, CHUNK_SIZE) ?: 0

                if (read > 0) {
                    // VU meter
                    var max = 0
                    for (i in 0 until read) {
                        val absVal = abs(captureBuffer[i].toInt())
                        if (absVal > max) max = absVal
                    }
                    latestPeakAmplitude = max

                    // Convert to bytes for network transmission
                    val payload = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) payload.putShort(captureBuffer[i])

                    // Send to network
                    onAudioDataCaptured?.invoke(payload.array())

                    // Local loopback (testing only)
                    if (enableLocalLoop) {
                        playRemoteAudio(System.currentTimeMillis(), payload.array())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CAPTURE] Error: ${e.message}")
        } finally {
            cleanupHardware()
        }
    }

    // ========================================================================
    // FIX: PROPER PLAYBACK ENGINE WITH PREFILL
    // ========================================================================
    private fun runPlaybackEngine() {
        val silenceBuffer = ShortArray(CHUNK_SIZE) // Pre-allocated zeros

        try {
            // === PREFILL PHASE ===
            Log.d(TAG, "[PLAYBACK] Waiting for buffer prefill...")

            var waitCount = 0
            val maxWait = 150 // 3 seconds max wait

            while (!reorderBuffer.isReady() && isRunning.get() && waitCount < maxWait) {
                Thread.sleep(20)
                waitCount++

                if (waitCount % 25 == 0) { // Log every 500ms
                    Log.d(TAG, "[PLAYBACK] Prefill waiting... ${reorderBuffer.getStats()}")
                }
            }

            if (!reorderBuffer.isReady()) {
                Log.w(TAG, "[PLAYBACK] Prefill timeout, starting anyway")
            } else {
                Log.d(TAG, "[PLAYBACK] Buffer ready: ${reorderBuffer.getStats()}")
            }

            // === PLAYBACK LOOP ===
            var consecutiveUnderruns = 0

            while (isRunning.get()) {
                val packet = reorderBuffer.take()

                if (packet == null) {
                    // UNDERRUN: No data available
                    underrunCount++
                    consecutiveUnderruns++

                    if (underrunCount % 10 == 0) {
                        Log.w(TAG, "[PLAYBACK] Underrun #$underrunCount (consecutive: $consecutiveUnderruns) - ${reorderBuffer.getStats()}")
                    }

                    // Write silence to maintain audio clock continuity
                    // This prevents "clicking" when buffer recovers
                    synchronized(hardwareLock) {
                        val track = audioTrack
                        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            track.write(silenceBuffer, 0, CHUNK_SIZE)
                        }
                    }

                    // Brief pause before retry
                    Thread.sleep(5)
                    continue
                }

                // Reset consecutive underrun counter
                consecutiveUnderruns = 0

                // Copy packet to playback buffer
                // Handle variable-size packets (though should always be CHUNK_SIZE)
                val copySize = minOf(packet.size, CHUNK_SIZE)
                for (i in 0 until copySize) {
                    playbackBuffer[i] = packet[i]
                }

                // Zero-fill if packet is undersized
                for (i in copySize until CHUNK_SIZE) {
                    playbackBuffer[i] = 0
                }

                // Write to AudioTrack
                // This call BLOCKS for ~10ms (at 16kHz), providing natural timing
                synchronized(hardwareLock) {
                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        track.write(playbackBuffer, 0, CHUNK_SIZE)
                    }
                }

                // Log buffer health periodically
                if (underrunCount > 0 && underrunCount % 50 == 0) {
                    Log.d(TAG, "[PLAYBACK] Stats: ${reorderBuffer.getStats()}")
                }
            }

            Log.d(TAG, "[PLAYBACK] Ended - Total underruns: $underrunCount")

        } catch (e: InterruptedException) {
            Log.d(TAG, "[PLAYBACK] Interrupted (normal shutdown)")
        } catch (e: Exception) {
            Log.e(TAG, "[PLAYBACK] Error: ${e.message}")
        }
    }

    // ========================================================================
    // FIX: ADAPTIVE BUFFER SIZING FOR BLUETOOTH
    // ========================================================================
    private fun initAudioHardware(rate: Int): Boolean {
        val minBufRecord = AudioRecord.getMinBufferSize(rate, channelInConfig, audioFormat)
        if (minBufRecord <= 0) {
            Log.e(TAG, "[INIT] Invalid buffer size for rate: $rate")
            return false
        }

        try {
            // AudioRecord: Larger buffer for capture stability
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                channelInConfig,
                audioFormat,
                minBufRecord * 2
            )

            // === ADAPTIVE AUDIOTRACK BUFFER ===
            // Bluetooth needs smaller buffer for low latency
            // Speakers can handle larger buffer for smoothness
            val isBtConnected = isBluetoothConnected()
            val trackBufferMultiplier = if (isBtConnected) 2 else 4

            val minBufTrack = AudioTrack.getMinBufferSize(rate, channelOutConfig, audioFormat)

            Log.d(TAG, "[INIT] Bluetooth: $isBtConnected, AudioTrack buffer: ${trackBufferMultiplier}x (${minBufTrack * trackBufferMultiplier} bytes)")

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(rate)
                        .setChannelMask(channelOutConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufTrack * trackBufferMultiplier) // ← ADAPTIVE
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val recordOk = audioRecord?.state == AudioRecord.STATE_INITIALIZED
            val trackOk = audioTrack?.state == AudioTrack.STATE_INITIALIZED

            Log.d(TAG, "[INIT] AudioRecord: $recordOk, AudioTrack: $trackOk")

            return recordOk && trackOk

        } catch (e: Exception) {
            Log.e(TAG, "[INIT] Failed: ${e.message}")
            return false
        }
    }

    private fun attachAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: return

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "[DSP] AEC enabled")
                }
            }

            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "[DSP] AGC enabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DSP] Error: ${e.message}")
        }
    }

    private fun cleanupHardware() {
        synchronized(hardwareLock) {
            try {
                // Release audio effects
                echoCanceler?.release()
                echoCanceler = null
                gainControl?.release()
                gainControl = null

                // Stop and release audio hardware
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

                // Bluetooth cleanup
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }

                audioManager.mode = AudioManager.MODE_NORMAL

                Log.d(TAG, "[CLEANUP] Complete - Total underruns: $underrunCount")

            } catch (e: Exception) {
                Log.e(TAG, "[CLEANUP] Error: ${e.message}")
            }
        }
    }

    fun stopLoopback() {
        isRunning.set(false)
        captureThread?.interrupt()
        playbackThread?.interrupt()
    }

    fun isActive() = isRunning.get()
}