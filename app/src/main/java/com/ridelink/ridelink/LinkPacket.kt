package com.ridelink.intercom

import java.nio.ByteBuffer

/**
 * PHASE 5: CHAIN LINK FOUNDATION
 * The multi-hop data envelope.
 */
data class LinkPacket(
    val originId: String,      // 16 chars
    val partyId: String,       // 16 chars
    val linkType: Int,         // 1=Prime, 2=Chain
    val priority: Int,
    val sequence: Long,
    val hopCount: Int,
    val payload: ByteArray
) {
    companion object {
        const val LINK_PRIME = 1
        const val LINK_CHAIN = 2
        const val PACKET_HEADER_SIZE = 52 // 16+16+4+4+8+4

        fun fromByteArray(data: ByteArray): LinkPacket {
            val buffer = ByteBuffer.wrap(data)
            val origin = ByteArray(16).also { buffer.get(it) }
            val party = ByteArray(16).also { buffer.get(it) }
            return LinkPacket(
                originId = String(origin).trim(),
                partyId = String(party).trim(),
                linkType = buffer.getInt(),
                priority = buffer.getInt(),
                sequence = buffer.getLong(),
                hopCount = buffer.getInt(),
                payload = ByteArray(data.size - PACKET_HEADER_SIZE).also { buffer.get(it) }
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_HEADER_SIZE + payload.size)
        buffer.put(originId.padEnd(16).toByteArray().take(16).toByteArray())
        buffer.put(partyId.padEnd(16).toByteArray().take(16).toByteArray())
        buffer.putInt(linkType)
        buffer.putInt(priority)
        buffer.putLong(sequence)
        buffer.putInt(hopCount)
        buffer.put(payload)
        return buffer.array()
    }
}