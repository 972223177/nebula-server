package com.nebula.service.sequence

import com.nebula.service.testutil.RedisTestBase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T06: SeqService Redis 重启恢复集成测试（D-14-01, D-81, H21）。
 *
 * 验证 [SeqService] 在 Redis 数据丢失（FLUSHALL）后，通过 [SeqService.tryRestoreSeq]
 * 从外部（MySQL）恢复序列号的能力。
 *
 * 使用 [RedisTestBase] 提供真实的 Redis 容器，避免 MockK 无法验证 Redis INCR/SETNX 行为的局限。
 *
 * 测试步骤：
 * 1. 使用真实 Lettuce 连接初始化 Redis 序列号
 * 2. 写入 5 条消息（seq 1-5）
 * 3. Redis FLUSHALL 模拟数据丢失
 * 4. 调用 tryRestoreSeq(convId, uid, 6L) 恢复
 * 5. 验证 nextSeq(convId, uid) 返回 6
 */
class SeqServiceRedisRecoveryTest : RedisTestBase() {

    private lateinit var seqService: SeqService
    private val convId = "conv-recovery"
    private val uid = 1001L

    @BeforeEach
    fun setUp() {
        // 通过基类提供的真实 Redis 连接构造 SeqService
        seqService = SeqService(getConnection())
    }

    /**
     * T06: Redis FLUSHALL 后序列号应从 tryRestoreSeq 恢复。
     *
     * 验证：
     * - nextSeq 在正常写入后正确递增
     * - FLUSHALL 清除所有数据后 currentSeq 返回 0
     * - tryRestoreSeq 使用 SETNX 幂等恢复起始序列号
     * - 恢复后 nextSeq 返回正确的下一序列号
     */
    @Test
    fun seqShouldContinueAfterRedisFlush() = runTest {
        // Given: 写入 5 条消息（seq 1-5）
        repeat(5) {
            seqService.nextSeq(convId, uid)
        }
        val beforeFlush = seqService.currentSeq(convId, uid)
        assertEquals(5L, beforeFlush, "写入 5 条消息后 currentSeq 应为 5")

        // When: 模拟 Redis 数据丢失
        getCommands().flushall()

        // 验证 FLUSHALL 后 currentSeq 返回 0
        val afterFlush = seqService.currentSeq(convId, uid)
        assertEquals(0L, afterFlush, "FLUSHALL 后 currentSeq 应为 0")

        // 调用恢复逻辑（SETNX 幂等设置）
        val restored = seqService.tryRestoreSeq(convId, uid, 6L)
        assertEquals(true, restored, "Key 不存在时 SETNX 应返回 true")

        // Then: 下一条消息序列号应为 6
        val nextSeq = seqService.nextSeq(convId, uid)
        assertEquals(6L, nextSeq, "恢复后 nextSeq 应返回 6")
    }

    /**
     * T06: Redis FLUSHALL 后 tryRestoreSeq 使用 SETNX 不会覆盖已有 Key。
     *
     * 验证 tryRestoreSeq 的幂等性：假设 MySQL 也丢失数据（nextSeq=1），
     * 但 Redis 中已有其他组件写入的序列号（Key 已存在），SETNX 不应覆盖。
     */
    @Test
    fun tryRestoreSeqShouldNotOverwriteExistingKey() = runTest {
        // Given: 先写入一条消息（Redis 中已有 Key）
        seqService.nextSeq(convId, uid + 1)
        val existingValue = seqService.currentSeq(convId, uid + 1)
        assertEquals(1L, existingValue, "首次写入后 currentSeq 应为 1")

        // When: 尝试恢复一个更小的序列号
        val restored = seqService.tryRestoreSeq(convId, uid + 1, 0L)

        // Then: SETNX 应返回 false（Key 已存在）
        assertEquals(false, restored, "Key 已存在时 SETNX 应返回 false")

        // 确认现有 Key 未被覆盖
        val currentSeq = seqService.currentSeq(convId, uid + 1)
        assertEquals(1L, currentSeq, "已有 Key 不应被覆盖")
    }
}
