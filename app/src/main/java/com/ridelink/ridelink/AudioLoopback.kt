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
 * PHASE 5.3 FINAL: BLUETOOTH-OPTIMIZED AUDIO CORE
 *
 * CRITICAL FIXES:
 * 1. Start with 8kHz for maximum Bluetooth compatibility
 * 2. SCO activation BEFORE hardware initialization
 * 3. Only check TYPE_BLUETOOTH_SCO (not A2DP)
 * 4. Fixed CHUNK_SIZE for consistent timing
 */
class AudioLoopback(private val context: Context) {

    private val TAG = "AudioLoopback"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // FIX 1: Start with 8kHz for Bluetooth compatibility
    private var sampleRate = 8000  // ← CHANGED from 16000
    private val channelInConfig = AudioFormat.CHANNEL_IN_MONO
    private val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    private val reorderBuffer = ReorderBuffer(maxDelayMs = 100)

    @Volatile private var latestPeakAmplitude: Int = 0
    @Volatile private var underrunCount = 0
    private val hardwareLock = Any()

    // FIX 2: Consistent 10ms chunks at 8kHz
    private val CHUNK_SIZE = 80  // ← CHANGED from 160 (10ms at 8kHz)
    private val captureBuffer = ShortArray(CHUNK_SIZE)
    private val playbackBuffer = ShortArray(CHUNK_SIZE)

    private var onAudioDataCaptured: ((ByteArray) -> Unit)? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    fun setStreamingCallback(callback: (ByteArray) -> Unit) {
        this.onAudioDataCaptured = callback
    }

    fun getPeakAmplitude(): Int = latestPeakAmplitude

    // FIX 3: Only check SCO (voice profile), not A2DP (music profile)
    private fun isBluetoothScoConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }  // ← REMOVED A2DP
    }

    private fun startBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            val scoDevice = commDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO  // ← ONLY SCO
            }
            scoDevice?.let {
                val success = audioManager.setCommunicationDevice(it)
                Log.d(TAG, "[BT] SCO routed to: ${it.productName} (success: $success)")
            } ?: run {
                Log.w(TAG, "[BT] No SCO device found, trying legacy method")
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } else {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(TAG, "[BT] SCO started (legacy)")
        }
    }

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

    // FIX 4: SCO activation BEFORE hardware initialization
    private fun runCaptureEngine(enableLocalLoop: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // CRITICAL: Start SCO BEFORE creating hardware
            val isBluetooth = isBluetoothScoConnected()
            if (isBluetooth) {
                startBluetoothSco()
                Thread.sleep(1500)  // ← INCREASED wait time for BT handshake
                Log.d(TAG, "[BT] SCO established, now creating hardware...")
            }

            // Now create hardware with correct sample rate
            synchronized(hardwareLock) {
                if (!initAudioHardware(sampleRate)) {
                    Log.e(TAG, "[INIT] Hardware initialization failed at ${sampleRate}Hz")
                    isRunning.set(false)
                    return
                }
                attachAudioEffects()
                audioRecord?.startRecording()
                audioTrack?.play()
            }

            Log.d(TAG, "[CAPTURE] Started at ${sampleRate}Hz, chunk=$CHUNK_SIZE samples (10ms)")

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

                    // Convert to bytes
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

    private fun runPlaybackEngine() {
        val silenceBuffer = ShortArray(CHUNK_SIZE)

        try {
            Log.d(TAG, "[PLAYBACK] Waiting for buffer prefill...")

            var waitCount = 0
            val maxWait = 150

            while (!reorderBuffer.isReady() && isRunning.get() && waitCount < maxWait) {
                Thread.sleep(20)
                waitCount++

                if (waitCount % 25 == 0) {
                    Log.d(TAG, "[PLAYBACK] Prefill waiting... ${reorderBuffer.getStats()}")
                }
            }

            if (!reorderBuffer.isReady()) {
                Log.w(TAG, "[PLAYBACK] Prefill timeout, starting anyway")
            } else {
                Log.d(TAG, "[PLAYBACK] Buffer ready: ${reorderBuffer.getStats()}")
            }

            var consecutiveUnderruns = 0

            while (isRunning.get()) {
                val packet = reorderBuffer.take()

                if (packet == null) {
                    underrunCount++
                    consecutiveUnderruns++

                    if (underrunCount % 10 == 0) {
                        Log.w(TAG, "[PLAYBACK] Underrun #$underrunCount (consecutive: $consecutiveUnderruns) - ${reorderBuffer.getStats()}")
                    }

                    synchronized(hardwareLock) {
                        val track = audioTrack
                        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            track.write(silenceBuffer, 0, CHUNK_SIZE)
                        }
                    }

                    Thread.sleep(5)
                    continue
                }

                consecutiveUnderruns = 0

                val copySize = minOf(packet.size, CHUNK_SIZE)
                for (i in 0 until copySize) {
                    playbackBuffer[i] = packet[i]
                }
                for (i in copySize until CHUNK_SIZE) {
                    playbackBuffer[i] = 0
                }

                synchronized(hardwareLock) {
                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        track.write(playbackBuffer, 0, CHUNK_SIZE)
                    }
                }

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

    private fun initAudioHardware(rate: Int): Boolean {
        val minBufRecord = AudioRecord.getMinBufferSize(rate, channelInConfig, audioFormat)
        if (minBufRecord <= 0) {
            Log.e(TAG, "[INIT] Invalid buffer size for rate: $rate")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                channelInConfig,
                audioFormat,
                minBufRecord * 2
            )

            // Smaller buffers for Bluetooth (lower latency)
            val trackBufferMultiplier = 2
            val minBufTrack = AudioTrack.getMinBufferSize(rate, channelOutConfig, audioFormat)

            Log.d(TAG, "[INIT] AudioTrack buffer: ${trackBufferMultiplier}x (${minBufTrack * trackBufferMultiplier} bytes)")

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
                .setBufferSizeInBytes(minBufTrack * trackBufferMultiplier)
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
                echoCanceler?.release()
                echoCanceler = null
                gainControl?.release()
                gainControl = null

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null

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