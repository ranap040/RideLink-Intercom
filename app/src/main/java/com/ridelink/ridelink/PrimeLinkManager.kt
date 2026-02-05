package com.ridelink.intercom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 5.2: PRIME LINK v2 - HARDENED WITH STATUS SYNC
 * Dedicated Rider-Pillion Bluetooth Link.
 */
@SuppressLint("MissingPermission")
class PrimeLinkManager(private val context: Context) {

    private val TAG = "PrimeLink"
    private val NAME = "RideLinkPrime"
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private var acceptThread: AcceptThread? = null
    private var connectThread: Thread? = null
    private var connectedThread: ConnectedThread? = null
    
    private val isRunning = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val MAX_RECONNECT_ATTEMPTS = 3
    private val packetSequence = AtomicLong(0)

    private var currentRole: Role = Role.NONE
    private var lastTargetDevice: BluetoothDevice? = null

    private var audioCallback: ((Long, ByteArray) -> Unit)? = null
    private var statusCallback: ((Status) -> Unit)? = null

    enum class Role { NONE, RIDER_HOST, PILLION_JOINER }
    enum class Status { DISCONNECTED, HOSTING, CONNECTED, RECONNECTING, FAILED }

    fun setCallbacks(audio: (Long, ByteArray) -> Unit, status: (Status) -> Unit) {
        this.audioCallback = audio
        this.statusCallback = status
    }

    fun startServer() {
        Log.d(TAG, "Prime Link: Hosting started...")
        currentRole = Role.RIDER_HOST
        reconnectAttempts.set(0)
        stopThreads()
        isRunning.set(true)
        
        // UI FIX: Immediately notify that we are now hosting
        statusCallback?.invoke(Status.HOSTING)
        
        acceptThread = AcceptThread().apply { start() }
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Prime Link: Joining ${device.name}...")
        currentRole = Role.PILLION_JOINER
        lastTargetDevice = device
        reconnectAttempts.set(0)
        stopThreads()
        isRunning.set(true)
        
        connectThread = Thread {
            try {
                val socket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
                socket.connect()
                manageConnectedSocket(socket)
            } catch (e: IOException) {
                if (isRunning.get()) handleDisconnection()
            }
        }.apply { start() }
    }

    fun sendAudio(data: ByteArray) {
        if (!isRunning.get() || data.isEmpty()) return
        val CHUNK_SIZE = 320
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            connectedThread?.write(packetSequence.incrementAndGet(), chunk)
            offset += CHUNK_SIZE
        }
    }

    private fun handleDisconnection() {
        if (!isRunning.get()) return
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt <= MAX_RECONNECT_ATTEMPTS) {
            statusCallback?.invoke(Status.RECONNECTING)
            Thread {
                Thread.sleep(2000)
                if (currentRole == Role.RIDER_HOST) startServer()
                else lastTargetDevice?.let { connectToDevice(it) }
            }.start()
        } else {
            statusCallback?.invoke(Status.FAILED)
            stopAll()
        }
    }

    fun stopAll() {
        isRunning.set(false)
        reconnectAttempts.set(MAX_RECONNECT_ATTEMPTS + 1)  // Prevent reconnection
        packetSequence.set(0)
        stopThreads()
        statusCallback?.invoke(Status.DISCONNECTED)
    }

    private fun stopThreads() {
        val oldAccept = acceptThread
        val oldConnected = connectedThread
        acceptThread = null
        connectedThread = null
        Thread {
            try { oldAccept?.cancel(); oldConnected?.cancel() } catch (e: Exception) {}
        }.start()
    }

    private inner class AcceptThread : Thread() {
        private var mmServerSocket: BluetoothServerSocket? = null
        override fun run() {
            try {
                mmServerSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)
                while (isRunning.get()) {
                    val socket = mmServerSocket?.accept()
                    socket?.let {
                        manageConnectedSocket(it)
                        mmServerSocket?.close()
                        return
                    }
                }
            } catch (e: Exception) {}
        }
        fun cancel() { try { mmServerSocket?.close() } catch (e: Exception) {} }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        reconnectAttempts.set(0)
        statusCallback?.invoke(Status.CONNECTED)
        connectedThread = ConnectedThread(socket).apply { start() }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream = DataInputStream(mmSocket.inputStream)
        private val mmOutStream = DataOutputStream(mmSocket.outputStream)

        override fun run() {
            try {
                while (isRunning.get()) {
                    val seq = mmInStream.readLong()
                    val len = mmInStream.readInt()
                    if (len in 1..2048) {
                        val buffer = ByteArray(len)
                        mmInStream.readFully(buffer)
                        audioCallback?.invoke(seq, buffer)
                    }
                }
            } catch (e: IOException) {
                handleDisconnection()
            } finally { cancel() }
        }

        fun write(seq: Long, data: ByteArray) {
            Thread {
                try {
                    synchronized(mmOutStream) {
                        mmOutStream.writeLong(seq)
                        mmOutStream.writeInt(data.size)
                        mmOutStream.write(data)
                        mmOutStream.flush()
                    }
                } catch (e: Exception) {}
            }.start()
        }

        fun cancel() { try { mmSocket.close() } catch (e: Exception) {} }
    }
}