package com.streamlink.shared.transport

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentSkipListMap

/**
 * TransportReliabilityManager acts as a lightweight reliable transport layer over raw TCP/UDP.
 * Features:
 * - Packet Sequence Management
 * - Packet Reordering (Jitter Buffer)
 * - Duplicate Detection
 * - Clock Drift Compensation & Synchronization
 */
class TransportReliabilityManager(private val bufferMs: Long = 100L) {
    private val tag = "ReliabilityManager"

    // Sequence Generators
    private val outgoingSequence = AtomicLong(0)
    
    // Incoming Sequence Tracking
    private val highestReceivedSeq = AtomicLong(-1)
    
    // Replay Protection (Zero-GC Bitmaps)
    private val replayWindowSizeBits = 128 // Configurable 64, 128, 256
    private val replayBitmaps = LongArray(replayWindowSizeBits / 64)
    
    // Jitter Buffer for reordering (Seq -> Packet/Data)
    // Using SkipListMap for ordered retrieval. In a true Zero-GC hot path, 
    // a pre-allocated RingBuffer with sequence modulo indexing is better, 
    // but for variable network arrival this is a good starting abstraction.
    private val jitterBuffer = ConcurrentSkipListMap<Long, Any>()

    // Clock Synchronization
    @Volatile
    private var clockOffsetMs: Long = 0L

    /**
     * Gets the next outgoing sequence number.
     */
    fun nextOutgoingSequence(): Long = outgoingSequence.incrementAndGet()

    /**
     * Called when a packet is received.
     * @return true if the packet should be processed (in-order or buffered successfully), 
     * false if it's a duplicate or too old.
     */
    fun onPacketReceived(seq: Long, payload: Any, timestampMs: Long): Boolean {
        val currentHighest = highestReceivedSeq.get()
        
        if (seq <= currentHighest) {
            val diff = currentHighest - seq
            if (diff >= replayWindowSizeBits) {
                Log.v(tag, "Dropped excessively late packet: $seq")
                return false // Too old, outside the replay window
            }
            
            // Check Replay Window Bitmap
            val bitmapIndex = (diff / 64).toInt()
            val bitPosition = (diff % 64).toInt()
            val mask = 1L shl bitPosition
            
            if ((replayBitmaps[bitmapIndex] and mask) != 0L) {
                Log.v(tag, "Dropped duplicate packet (Replay Protection): $seq")
                return false // Duplicate packet
            }
            
            // Mark packet as received
            replayBitmaps[bitmapIndex] = replayBitmaps[bitmapIndex] or mask
        } else {
            // New highest packet received
            val diff = seq - currentHighest
            if (diff >= replayWindowSizeBits) {
                // Massive jump, clear bitmaps
                for (i in replayBitmaps.indices) {
                    replayBitmaps[i] = 0L
                }
            } else {
                // Shift bitmaps
                val shiftElements = (diff / 64).toInt()
                val shiftBits = (diff % 64).toInt()
                
                // Array shift logic (Simplified for fixed 128 / 2-element array for speed)
                if (shiftElements >= replayBitmaps.size) {
                    replayBitmaps[0] = 0L
                    replayBitmaps[1] = 0L
                } else {
                    if (shiftElements == 1) {
                        replayBitmaps[1] = replayBitmaps[0]
                        replayBitmaps[0] = 0L
                    }
                    if (shiftBits > 0) {
                        // Shift across elements
                        replayBitmaps[1] = (replayBitmaps[1] shl shiftBits) or (replayBitmaps[0] ushr (64 - shiftBits))
                        replayBitmaps[0] = replayBitmaps[0] shl shiftBits
                    }
                }
            }
            // Mark the new sequence (bit 0 of bitmap[0])
            replayBitmaps[0] = replayBitmaps[0] or 1L
            highestReceivedSeq.set(seq)
        }

        // Buffer the packet
        jitterBuffer[seq] = payload
        
        return true
    }

    /**
     * Retrieves ordered packets that are ready for consumption.
     */
    fun consumeOrderedPackets(upToSeq: Long): List<Any> {
        val ready = mutableListOf<Any>()
        val iterator = jitterBuffer.entries.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key <= upToSeq) {
                ready.add(entry.value)
                iterator.remove()
            } else {
                break // Ordered list, break early
            }
        }
        return ready
    }

    /**
     * Updates the clock offset between Phone and Watch.
     */
    fun updateClockOffset(remoteTimeMs: Long, localTimeMs: Long) {
        val offset = remoteTimeMs - localTimeMs
        // Moving average for drift compensation
        clockOffsetMs = if (clockOffsetMs == 0L) {
            offset
        } else {
            (clockOffsetMs * 0.9 + offset * 0.1).toLong()
        }
    }

    fun getSynchronizedTimeMs(localTimeMs: Long): Long {
        return localTimeMs + clockOffsetMs
    }
}
