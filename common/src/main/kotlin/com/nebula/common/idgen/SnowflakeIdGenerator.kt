package com.nebula.common.idgen

import com.nebula.common.exception.ClockBackwardsException

/**
 * Snowflake ID generator.
 *
 * Bit allocation: 41 bits timestamp (ms) | 10 bits worker ID | 12 bits sequence
 *
 * @param workerId Worker ID (0~1023)
 * @param epoch Custom epoch in milliseconds (default: 2023-11-14T21:33:20.000Z)
 */
class SnowflakeIdGenerator(
    val workerId: Long,
    val epoch: Long = 1700000000000L
) {

    companion object {
        private const val WORKER_ID_BITS = 10L
        private const val SEQUENCE_BITS = 12L
        private const val MAX_WORKER_ID = (1L shl WORKER_ID_BITS.toInt()) - 1
        private const val SEQUENCE_MASK = (1L shl SEQUENCE_BITS.toInt()) - 1
        private const val WORKER_ID_SHIFT = SEQUENCE_BITS
        private const val TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS
    }

    init {
        require(workerId in 0..MAX_WORKER_ID) {
            "Worker ID must be between 0 and $MAX_WORKER_ID, got $workerId"
        }
    }

    private var lastTimestamp = -1L
    private var sequence = 0L

    @Synchronized
    fun nextId(): Long {
        var timestamp = currentTimeMillis()

        if (timestamp < lastTimestamp) {
            val diff = lastTimestamp - timestamp
            throw ClockBackwardsException("Clock moved backwards $diff ms from $lastTimestamp to $timestamp")
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) and SEQUENCE_MASK
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp)
            }
        } else {
            sequence = 0L
        }

        lastTimestamp = timestamp
        return ((timestamp - epoch) shl TIMESTAMP_SHIFT.toInt()) or
                (workerId shl WORKER_ID_SHIFT.toInt()) or
                sequence
    }

    private fun waitNextMillis(lastTimestamp: Long): Long {
        var timestamp = currentTimeMillis()
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis()
        }
        return timestamp
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()
}
