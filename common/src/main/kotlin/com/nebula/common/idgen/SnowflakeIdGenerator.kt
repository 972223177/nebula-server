package com.nebula.common.idgen

import com.nebula.common.exception.ClockBackwardsException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 雪花算法（Snowflake）ID 生成器，用于在分布式环境下生成全局唯一的 64 位长整型 ID。
 *
 * 位分配（高位在左）：
 *   | 1 bit 保留（始终为 0）| 41 bits 时间戳（毫秒级，相对自定义 epoch）| 10 bits 工作节点 ID | 12 bits 序列号 |
 *
 * - 时间戳 41 bits 可支撑约 69 年
 * - 工作节点 ID 10 bits 支持最多 1024 个节点
 * - 序列号 12 bits 每毫秒最多生成 4096 个 ID，单节点若超限则等待下一毫秒
 *
 * 由于时间戳相对 [epoch] 存储，选择较近的 epoch 可延长该生成器可用年限。
 *
 * @param workerId 工作节点 ID（0~1023），分布式下每实例必须唯一
 * @param epoch 自定义纪元起点（毫秒时间戳），默认 2023-11-14T21:33:20.000Z
 */
class SnowflakeIdGenerator(
    /** 工作节点 ID（0~1023），分布式下每实例必须唯一，ID 冲突会导致跨节点消息乱序 */
    val workerId: Long,
    /** 自定义纪元起点（毫秒时间戳），前移基准时间以延长 41 bit 时间戳可用年限 */
    val epoch: Long = 1700000000000L
) {

    companion object {
        // 工作节点 ID 占用位数
        private const val WORKER_ID_BITS = 10L
        // 序列号占用位数
        private const val SEQUENCE_BITS = 12L
        // 工作节点 ID 最大值 1023（2^10 - 1）
        private const val MAX_WORKER_ID = (1L shl WORKER_ID_BITS.toInt()) - 1
        // 序列号掩码 4095（2^12 - 1），用于截断递增后的序列号
        private const val SEQUENCE_MASK = (1L shl SEQUENCE_BITS.toInt()) - 1

        // 序列号在最终 ID 中的左移位数（低 12 位留给序列号）
        private const val WORKER_ID_SHIFT = SEQUENCE_BITS
        // 时间戳在最终 ID 中的左移位数（低 22 位留给 工作节点 + 序列号）
        private const val TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS
    }

    init {
        // 启动时校验 workerId 合法性，避免运行时产生不可察觉的 ID 冲突
        require(workerId in 0..MAX_WORKER_ID) {
            "Worker ID must be between 0 and $MAX_WORKER_ID, got $workerId"
        }
    }

    /** 上一次生成 ID 的时间戳，用于检测时钟回拨和毫秒内序列溢出 */
    private var lastTimestamp = -1L
    /** 当前毫秒内的序列号，溢出后自旋等待下一毫秒 */
    private var sequence = 0L

    /** 协程互斥锁，替代 @Synchronized 以避免协程线程阻塞 */
    private val mutex = Mutex()

    /**
     * 生成下一个全局唯一 ID。
     *
     * 使用 [Mutex] 替代 @Synchronized 保证线程安全，避免在协程上下文中阻塞线程。
     *
     * @return 64 位长整型 ID
     */
    suspend fun nextId(): Long = mutex.withLock {
        var timestamp = currentTimeMillis()

        // 检测时钟回拨 —— 若系统时间倒退则抛异常，防止 ID 重复
        if (timestamp < lastTimestamp) {
            val diff = lastTimestamp - timestamp
            throw ClockBackwardsException("Clock moved backwards $diff ms from $lastTimestamp to $timestamp")
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内：递增序列号并用掩码截断，溢出归零
            sequence = (sequence + 1) and SEQUENCE_MASK
            if (sequence == 0L) {
                // 序列号已用完（此毫秒已生成 4096 个 ID），自旋等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp)
            }
        } else {
            // 新的一毫秒：序列号归零，避免浪费 bits
            sequence = 0L
        }

        lastTimestamp = timestamp
        // 拼装最终 64 位 ID：
        //   (相对时间戳) << 22  |  (workerId) << 12  |  序列号
        ((timestamp - epoch) shl TIMESTAMP_SHIFT.toInt()) or
                (workerId shl WORKER_ID_SHIFT.toInt()) or
                sequence
    }

    /**
     * 忙等待直到系统时间越过 [lastTimestamp]。
     *
     * 不采用 Thread.sleep 以避免不可预测的唤醒延迟，尤其在毫秒粒度下 sleep(1)
     * 实际可能休眠 2~15ms，自旋消耗 CPU 但延迟远低于 sleep。
     *
     * @param lastTimestamp 上次生成 ID 时记录的时间戳
     * @return 越过 lastTimestamp 后的当前毫秒时间戳
     */
    private fun waitNextMillis(lastTimestamp: Long): Long {
        var timestamp = currentTimeMillis()
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis()
        }
        return timestamp
    }

    /**
     * 获取当前系统毫秒时间戳。
     *
     * 抽象为独立方法便于单元测试时注入模拟时钟，生产环境使用 System.currentTimeMillis()。
     *
     * @return 当前系统毫秒时间戳
     */
    private fun currentTimeMillis(): Long = System.currentTimeMillis()
}
