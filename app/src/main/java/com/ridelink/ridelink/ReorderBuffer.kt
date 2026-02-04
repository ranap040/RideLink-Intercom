package com.ridelink.intercom

import android.util.Log
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PHASE 5.2 FIXED: ADAPTIVE REORDER BUFFER
 *
 * CRITICAL FIXES from Phase 5.1:
 * 1. Removed the catastrophic 3-packet limit (was causing starvation)
 * 2. Added adaptive pre-fill measurement
 * 3. Implemented intelligent late-packet handling
 * 4. Dynamic buffer depth based on actual network jitter
 *
 * Engineering Notes:
 * - Queue size must scale with network conditions
 * - 1-to-1 Bluetooth: ~5 packets (50ms) is optimal
 * - 3-device mesh: ~12 packets (120ms) handles typical jitter
 * - Clock drift compensation: Drop oldest when >20 packets accumulated
 */
class ReorderBuffer(
    private val maxDelayMs: Int = 100
) {
    private val TAG = "ReorderBuffer"

    // Priority queue: auto-sorts by sequence number
    private val queue = PriorityQueue<PacketWrapper>(50) { a, b ->
        a.sequence.compareTo(b.sequence)
    }
    private val lock = ReentrantLock()

    private var nextExpectedSequence = -1L
    private var packetsReceived = 0
    private var packetsDropped = 0

    // Adaptive prefill tracking
    private var firstPacketTime = 0L
    private var prefillMeasured = false
    private var targetPrefill = 5 // Default: 50ms buffer

    data class PacketWrapper(
        val sequence: Long,
        val data: ShortArray,
        val arrivalTime: Long = System.currentTimeMillis()
    )

    /**
     * Insert incoming packet with intelligent buffering
     */
    fun insert(sequence: Long, data: ShortArray) {
        lock.withLock {
            // Initialize on first packet
            if (nextExpectedSequence == -1L) {
                nextExpectedSequence = sequence
                firstPacketTime = System.currentTimeMillis()
                Log.d(TAG, "[INIT] Starting sequence: $sequence")
            }

            // === FIX #1: Proper old packet handling ===
            // Drop packets that are too old (already played or skipped)
            if (sequence < nextExpectedSequence) {
                packetsDropped++
                if (packetsDropped % 10 == 0) {
                    Log.w(TAG, "[DROP] Late packet seq=$sequence (expected=$nextExpectedSequence), total dropped=$packetsDropped")
                }
                return
            }

            // Add to queue
            queue.offer(PacketWrapper(sequence, data))
            packetsReceived++

            // === FIX #2: Adaptive prefill measurement ===
            // After receiving 5 packets, calculate optimal buffer depth
            if (!prefillMeasured && packetsReceived == 5) {
                val timeForFivePackets = System.currentTimeMillis() - firstPacketTime
                val avgInterPacketTime = timeForFivePackets / 5

                // Target buffer = 3x average inter-packet time
                // This gives us enough cushion for jitter
                targetPrefill = maxOf(5, minOf(15, (avgInterPacketTime * 3 / 10).toInt()))
                prefillMeasured = true

                Log.d(TAG, "[ADAPTIVE] Measured arrival: ~${avgInterPacketTime}ms/packet, target buffer: $targetPrefill packets")
            }

            // === FIX #3: Intelligent buffer overflow handling ===
            // Instead of hard limit at 3 packets (catastrophic!), allow adaptive growth
            // Only drop when we have SEVERE clock drift accumulation
            val MAX_BUFFER_SIZE = 20 // 200ms max - this handles clock drift

            if (queue.size > MAX_BUFFER_SIZE) {
                // Clock drift detected: sender is consistently faster than receiver
                // Drop oldest packet to prevent infinite buffer growth
                val dropped = queue.poll()
                if (dropped != null) {
                    // Adjust expected sequence to skip the gap
                    if (dropped.sequence >= nextExpectedSequence) {
                        nextExpectedSequence = dropped.sequence + 1
                    }
                    packetsDropped++

                    if (packetsDropped % 5 == 0) {
                        Log.w(TAG, "[CLOCK_DRIFT] Buffer overflow (${queue.size}), dropped seq=${dropped.sequence}")
                    }
                }
            }
        }
    }

    /**
     * Retrieve next packet with intelligent gap handling
     */
    fun take(): ShortArray? {
        lock.withLock {
            if (queue.isEmpty()) return null

            val head = queue.peek()!!

            // === CASE 1: Perfect sequence match ===
            if (head.sequence == nextExpectedSequence) {
                nextExpectedSequence++
                return queue.poll().data
            }

            // === CASE 2: We're ahead (gap in sequence) ===
            // This means we're missing packet(s) in the sequence
            if (head.sequence > nextExpectedSequence) {
                val gap = head.sequence - nextExpectedSequence
                val waitTime = System.currentTimeMillis() - head.arrivalTime

                // === FIX #4: Adaptive gap timeout ===
                // Small gap (1-2 packets): Wait up to 30ms for missing packet
                // Large gap (3+ packets): Skip immediately (likely packet loss)
                val timeoutMs = if (gap <= 2) 30L else 10L

                if (waitTime > timeoutMs) {
                    // Missing packet(s) won't arrive, skip the gap
                    Log.w(TAG, "[GAP] Skipping sequence $nextExpectedSequence to ${head.sequence} (gap=$gap, waited=${waitTime}ms)")
                    nextExpectedSequence = head.sequence + 1
                    return queue.poll().data
                }

                // Still waiting for missing packet
                return null
            }

            // === CASE 3: We're behind (shouldn't happen due to insert() filtering) ===
            // This is a safety check
            Log.e(TAG, "[ERROR] Unexpected state: head.seq=${head.sequence} < expected=$nextExpectedSequence")
            queue.poll() // Remove the packet
            return null
        }
    }

    /**
     * Check if buffer is ready for playback (adaptive prefill)
     */
    fun isReady(): Boolean {
        lock.withLock {
            return queue.size >= targetPrefill ||
                    (prefillMeasured && queue.isNotEmpty()) // After prefill, any data is OK
        }
    }

    /**
     * Get current buffer health
     */
    fun getBufferSize(): Int {
        lock.withLock {
            return queue.size
        }
    }

    /**
     * Get buffer statistics
     */
    fun getStats(): String {
        lock.withLock {
            return "Buffer: ${queue.size}/$targetPrefill, Received: $packetsReceived, Dropped: $packetsDropped"
        }
    }

    /**
     * Reset buffer state
     */
    fun clear() {
        lock.withLock {
            queue.clear()
            nextExpectedSequence = -1L
            packetsReceived = 0
            packetsDropped = 0
            firstPacketTime = 0L
            prefillMeasured = false
            targetPrefill = 5
            Log.d(TAG, "[RESET] Buffer cleared")
        }
    }
}