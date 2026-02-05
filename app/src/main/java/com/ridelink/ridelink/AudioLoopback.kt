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
 * PHASE 5.6 FINAL: TWS CRACKLE FIX
 *
 * KEY CHANGE: Larger AudioTrack buffer for Bluetooth (4x instead of 2x)
 * This prevents Bluetooth SCO underruns that cause crackling
 */
class AudioLoopback(private val context: Context) {

    private val TAG = "AudioLoopback"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val SAMPLE_RATE = 8000
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
    @Volatile private var capturePacketCount = 0
    private val hardwareLock = Any()

    private val CHUNK_SIZE = 80
    private val captureBuffer = ShortArray(CHUNK_SIZE)
    private val playbackBuffer = ShortArray(CHUNK_SIZE)

    private var onAudioDataCaptured: ((ByteArray) -> Unit)? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    fun setStreamingCallback(callback: (ByteArray) -> Unit) {
        this.onAudioDataCaptured = callback
    }

    fun getPeakAmplitude(): Int = latestPeakAmplitude

    private fun isBluetoothScoConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun startBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            val scoDevice = commDevices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            scoDevice?.let {
                val success = audioManager.setCommunicationDevice(it)
                Log.d(TAG, "[BT] ✓ SCO routed to: ${it.productName} (success: $success)")
            } ?: run {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                Log.d(TAG, "[BT] ✓ SCO started (fallback)")
            }
        } else {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(TAG, "[BT] ✓ SCO started (legacy)")
        }
    }

    fun playRemoteAudio(sequence: Long, data: ByteArray) {
        if (data.isEmpty() || data.size % 2 != 0) return

        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        reorderBuffer.insert(sequence, shorts)
    }

    fun startLoopback(enableLocalLoop: Boolean = false): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "[START] Already running, ignoring")
            return false
        }

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "[START] Initializing Audio Core")
        Log.d(TAG, "═══════════════════════════════════════")

        isRunning.set(true)
        reorderBuffer.clear()
        underrunCount = 0
        capturePacketCount = 0

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
            Log.d(TAG, "[CAPTURE] Thread started")

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "[CAPTURE] Audio mode set to IN_COMMUNICATION")

            val isBluetooth = isBluetoothScoConnected()
            if (isBluetooth) {
                Log.d(TAG, "[CAPTURE] Bluetooth SCO detected, activating...")
                startBluetoothSco()
                Thread.sleep(1500)
                Log.d(TAG, "[CAPTURE] SCO link established")
            } else {
                Log.d(TAG, "[CAPTURE] No Bluetooth, using earpiece/speaker")
            }

            synchronized(hardwareLock) {
                Log.d(TAG, "[CAPTURE] Initializing hardware at ${SAMPLE_RATE}Hz...")
                if (!initAudioHardware(isBluetooth)) {
                    Log.e(TAG, "[CAPTURE] ✗ CRITICAL: Hardware init failed!")
                    isRunning.set(false)
                    return
                }
                Log.d(TAG, "[CAPTURE] ✓ Hardware initialized")

                attachAudioEffects()
                audioRecord?.startRecording()
                audioTrack?.play()
            }

            Log.d(TAG, "[CAPTURE] ✓✓✓ ACTIVE at ${SAMPLE_RATE}Hz, chunk=${CHUNK_SIZE} samples (10ms)")

            while (isRunning.get()) {
                val read = audioRecord?.read(captureBuffer, 0, CHUNK_SIZE) ?: 0

                if (read > 0) {
                    capturePacketCount++

                    var max = 0
                    for (i in 0 until read) {
                        val absVal = abs(captureBuffer[i].toInt())
                        if (absVal > max) max = absVal
                    }
                    latestPeakAmplitude = max

                    val payload = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) payload.putShort(captureBuffer[i])

                    onAudioDataCaptured?.invoke(payload.array())

                    if (capturePacketCount % 100 == 0) {
                        Log.d(TAG, "[CAPTURE] Sent $capturePacketCount packets (amplitude: $max)")
                    }

                    if (enableLocalLoop) {
                        playRemoteAudio(System.currentTimeMillis(), payload.array())
                    }
                } else if (read < 0) {
                    Log.e(TAG, "[CAPTURE] AudioRecord error: $read")
                }
            }

            Log.d(TAG, "[CAPTURE] Loop ended normally (sent $capturePacketCount packets)")

        } catch (e: Exception) {
            Log.e(TAG, "[CAPTURE] ✗ Exception: ${e.message}", e)
        } finally {
            cleanupHardware()
        }
    }

    private fun runPlaybackEngine() {
        val silenceBuffer = ShortArray(CHUNK_SIZE)

        try {
            Log.d(TAG, "[PLAYBACK] Thread started, waiting for prefill...")

            var waitCount = 0
            val maxWait = 150

            while (!reorderBuffer.isReady() && isRunning.get() && waitCount < maxWait) {
                Thread.sleep(20)
                waitCount++

                if (waitCount % 25 == 0) {
                    Log.d(TAG, "[PLAYBACK] Prefill: ${reorderBuffer.getStats()}")
                }
            }

            if (reorderBuffer.isReady()) {
                Log.d(TAG, "[PLAYBACK] ✓✓✓ READY: ${reorderBuffer.getStats()}")
            } else {
                Log.w(TAG, "[PLAYBACK] ⚠ Prefill timeout, starting anyway")
            }

            var consecutiveUnderruns = 0
            var playbackPacketCount = 0

            while (isRunning.get()) {
                val packet = reorderBuffer.take()

                if (packet == null) {
                    underrunCount++
                    consecutiveUnderruns++

                    if (underrunCount % 100 == 0) {
                        Log.w(TAG, "[PLAYBACK] Underrun #$underrunCount - ${reorderBuffer.getStats()}")
                    }

                    synchronized(hardwareLock) {
                        audioTrack?.write(silenceBuffer, 0, CHUNK_SIZE)
                    }

                    Thread.sleep(5)
                    continue
                }

                consecutiveUnderruns = 0
                playbackPacketCount++

                val copySize = minOf(packet.size, CHUNK_SIZE)
                for (i in 0 until copySize) {
                    playbackBuffer[i] = packet[i]
                }
                for (i in copySize until CHUNK_SIZE) {
                    playbackBuffer[i] = 0
                }

                synchronized(hardwareLock) {
                    audioTrack?.write(playbackBuffer, 0, CHUNK_SIZE)
                }

                if (playbackPacketCount % 100 == 0) {
                    Log.d(TAG, "[PLAYBACK] Played $playbackPacketCount packets - ${reorderBuffer.getStats()}")
                }
            }

            Log.d(TAG, "[PLAYBACK] Ended (played $playbackPacketCount packets, underruns: $underrunCount)")

        } catch (e: InterruptedException) {
            Log.d(TAG, "[PLAYBACK] Interrupted (normal shutdown)")
        } catch (e: Exception) {
            Log.e(TAG, "[PLAYBACK] ✗ Exception: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CRITICAL FIX FOR TWS CRACKLING
    // ═══════════════════════════════════════════════════════════════════════
    private fun initAudioHardware(isBluetoothSco: Boolean): Boolean {
        val minBufRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelInConfig, audioFormat)
        if (minBufRecord <= 0) {
            Log.e(TAG, "[INIT] ✗ ${SAMPLE_RATE}Hz not supported! (minBuf: $minBufRecord)")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                channelInConfig,
                audioFormat,
                minBufRecord * 2
            )

            val minBufTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelOutConfig, audioFormat)

            // ═══════════════════════════════════════════════════════════════
            // FIX: Bluetooth needs LARGER buffer to prevent crackling
            // ═══════════════════════════════════════════════════════════════
            val trackBufferMultiplier = if (isBluetoothSco) 4 else 2  // ← CHANGED from 2

            Log.d(TAG, "[INIT] Bluetooth: $isBluetoothSco, Buffer multiplier: ${trackBufferMultiplier}x")
            Log.d(TAG, "[INIT] AudioTrack buffer size: ${minBufTrack * trackBufferMultiplier} bytes")

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
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelOutConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufTrack * trackBufferMultiplier)  // ← APPLIED HERE
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val recordOk = audioRecord?.state == AudioRecord.STATE_INITIALIZED
            val trackOk = audioTrack?.state == AudioTrack.STATE_INITIALIZED

            Log.d(TAG, "[INIT] AudioRecord: ${if(recordOk) "✓" else "✗"}, AudioTrack: ${if(trackOk) "✓" else "✗"}")

            return recordOk && trackOk

        } catch (e: Exception) {
            Log.e(TAG, "[INIT] ✗ Exception: ${e.message}", e)
            return false
        }
    }

    private fun attachAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: return

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "[DSP] AEC ✓")
                }
            }

            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "[DSP] AGC ✓")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DSP] Error: ${e.message}")
        }
    }

    private fun cleanupHardware() {
        synchronized(hardwareLock) {
            try {
                Log.d(TAG, "[CLEANUP] Releasing hardware...")

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

                Log.d(TAG, "[CLEANUP] ✓ Complete (packets: $capturePacketCount, underruns: $underrunCount)")

            } catch (e: Exception) {
                Log.e(TAG, "[CLEANUP] Error: ${e.message}")
            }
        }
    }

    fun stopLoopback() {
        Log.d(TAG, "[STOP] Stopping loopback")
        isRunning.set(false)
        captureThread?.interrupt()
        playbackThread?.interrupt()
    }

    fun isActive() = isRunning.get()
}