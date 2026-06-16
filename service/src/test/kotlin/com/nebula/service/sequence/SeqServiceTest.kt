package com.nebula.service.sequence

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SeqService] 的单元测试（D-81, H21）。
 *
 * 测试策略：
 * - 通过反射注入 mock [RedisCoroutinesCommands]，绕过 Lettuce 内部类
 *   [io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl] 的构造依赖
 * - 每个测试用例独立配置 mock 返回值，避免状态污染
 * - 覆盖 nextSeq 正常递增、溢出重置、currentSeq 查询、tryRestoreSeq SETNX 语义
 *
 * 注意：由于 SeqService 构造函数需要真实的 [StatefulRedisConnection] 来初始化
 * 内部的 [RedisCoroutinesCommandsImpl]，测试使用 relaxed mock connection 让
 * RedisCoroutinesCommandsImpl 能够构造（其构造函数仅存储 reactive commands 引用），
 * 然后通过反射将私有的 `redis` 字段替换为可控的 mock 对象。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SeqServiceTest {

    private val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var seqService: SeqService

    /**
     * 每个测试用例前初始化 SeqService 并用反射注入 mock redis。
     *
     * RedisCoroutinesCommandsImpl(reactiveCommands) 仅存储 reactive commands 引用，
     * relaxed mock 的 reactive() 返回非 null mock 即可安全构造。
     */
    @BeforeEach
    fun setUp() {
        redis = mockk(relaxed = true)
        seqService = SeqService(connection)
        injectRedisMock(redis)
    }

    /**
     * 通过反射将 private 字段 `redis` 替换为指定的 mock 实例。
     */
    private fun injectRedisMock(mock: RedisCoroutinesCommands<String, String>) {
        val field = SeqService::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(seqService, mock)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // nextSeq — 正常递增
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun nextSeqFirstCallReturnsOne() = runTest {
        // Given: Key 不存在（get 返回 null），INCR 后返回 1
        val convId = "conv-1"
        val uid = 1001L
        val redisKey = "seq:conv:conv-1:next_seq:uid:1001"

        coEvery { redis.get(redisKey) } returns null
        coEvery { redis.incr(redisKey) } returns 1L

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then
        assertEquals(1L, seq, "首次调用应返回 1")
        coVerify(exactly = 1) { redis.get(redisKey) }
        coVerify(exactly = 1) { redis.incr(redisKey) }
    }

    @Test
    fun nextSeqNormalIncrement() = runTest {
        // Given: 当前值为 42，INCR 后变为 43
        val convId = "conv-normal"
        val uid = 2001L
        val redisKey = "seq:conv:conv-normal:next_seq:uid:2001"

        coEvery { redis.get(redisKey) } returns "42"
        coEvery { redis.incr(redisKey) } returns 43L

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then
        assertEquals(43L, seq, "应返回自增后的值")
    }

    @Test
    fun nextSeqIncrReturnsNullFallbackZero() = runTest {
        // Given: INCR 异常返回 null（Redis 连接失败等极端情况）
        val convId = "conv-null"
        val uid = 3001L
        val redisKey = "seq:conv:conv-null:next_seq:uid:3001"

        coEvery { redis.get(redisKey) } returns "10"
        coEvery { redis.incr(redisKey) } returns null

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then: ?: 0L 兜底
        assertEquals(0L, seq, "INCR 返回 null 时应兜底返回 0")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // nextSeq — 溢出重置
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun nextSeqOverflowResetsToOne() = runTest {
        // Given: 当前值已达 MAX_SEQ_THRESHOLD（Long.MAX_VALUE - 10000），应触发重置
        val convId = "conv-overflow"
        val uid = 4001L
        val redisKey = "seq:conv:conv-overflow:next_seq:uid:4001"

        coEvery { redis.get(redisKey) } returns SeqService.MAX_SEQ_THRESHOLD.toString()
        coEvery { redis.set(redisKey, "1") } returns "OK"
        coEvery { redis.incr(redisKey) } returns 2L

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then: 重置后 INCR 返回 2
        assertEquals(2L, seq, "溢出重置后应返回自增后的值")
        coVerify(exactly = 1) { redis.set(redisKey, "1") }
        coVerify(exactly = 1) { redis.incr(redisKey) }
    }

    @Test
    fun nextSeqBelowThresholdNoReset() = runTest {
        // Given: 当前值远低于溢出阈值
        val convId = "conv-safe"
        val uid = 5001L
        val redisKey = "seq:conv:conv-safe:next_seq:uid:5001"

        coEvery { redis.get(redisKey) } returns "100"
        coEvery { redis.incr(redisKey) } returns 101L

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then: 不调用 set 重置，正常 INCR
        assertEquals(101L, seq)
        coVerify(exactly = 0) { redis.set(any(), any()) }
    }

    @Test
    fun nextSeqNonNumericValueNoReset() = runTest {
        // Given: get 返回非数字字符串（Redis 数据损坏）
        val convId = "conv-corrupt"
        val uid = 6001L
        val redisKey = "seq:conv:conv-corrupt:next_seq:uid:6001"

        coEvery { redis.get(redisKey) } returns "corrupted"
        coEvery { redis.incr(redisKey) } returns 1L

        // When
        val seq = seqService.nextSeq(convId, uid)

        // Then: toLongOrNull 返回 null，跳过重置，正常 INCR
        assertEquals(1L, seq)
        coVerify(exactly = 0) { redis.set(any(), any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // currentSeq — 查询当前序列号
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun currentSeqNormalQueryReturnsValue() = runTest {
        // Given: Key 存在且值为 42
        val convId = "conv-curr"
        val uid = 7001L
        val redisKey = "seq:conv:conv-curr:next_seq:uid:7001"

        coEvery { redis.get(redisKey) } returns "42"

        // When
        val seq = seqService.currentSeq(convId, uid)

        // Then
        assertEquals(42L, seq)
    }

    @Test
    fun currentSeqKeyNotExistsReturnsZero() = runTest {
        // Given: Key 不存在
        val convId = "conv-missing"
        val uid = 8001L
        val redisKey = "seq:conv:conv-missing:next_seq:uid:8001"

        coEvery { redis.get(redisKey) } returns null

        // When
        val seq = seqService.currentSeq(convId, uid)

        // Then
        assertEquals(0L, seq, "Key 不存在时应返回 0")
    }

    @Test
    fun currentSeqInvalidValueReturnsZero() = runTest {
        // Given: 值为非数字字符串（数据损坏）
        val convId = "conv-bad-value"
        val uid = 9001L
        val redisKey = "seq:conv:conv-bad-value:next_seq:uid:9001"

        coEvery { redis.get(redisKey) } returns "not-a-number"

        // When
        val seq = seqService.currentSeq(convId, uid)

        // Then: toLongOrNull 返回 null → 兜底 0
        assertEquals(0L, seq, "无效值时应兜底返回 0")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // tryRestoreSeq — SETNX 幂等恢复
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun tryRestoreSeqKeyNotExistsSetnxReturnsTrue() = runTest {
        // Given: Key 不存在，SETNX 应成功
        val convId = "conv-restore-new"
        val uid = 10001L
        val nextSeq = 42L
        val redisKey = "seq:conv:conv-restore-new:next_seq:uid:10001"

        coEvery { redis.setnx(redisKey, "42") } returns true

        // When
        val result = seqService.tryRestoreSeq(convId, uid, nextSeq)

        // Then: SETNX 成功，表示 Key 之前不存在且已设置
        assertTrue(result, "Key 不存在时应返回 true")
    }

    @Test
    fun tryRestoreSeqKeyExistsSetnxReturnsFalse() = runTest {
        // Given: Key 已存在，SETNX 应失败
        val convId = "conv-restore-exists"
        val uid = 20001L
        val nextSeq = 100L
        val redisKey = "seq:conv:conv-restore-exists:next_seq:uid:20001"

        coEvery { redis.setnx(redisKey, "100") } returns false

        // When
        val result = seqService.tryRestoreSeq(convId, uid, nextSeq)

        // Then: SETNX 失败，表示 Key 已存在（未被覆盖）
        assertFalse(result, "Key 已存在时应返回 false")
    }

    @Test
    fun tryRestoreSeqSetnxNullFallbackFalse() = runTest {
        // Given: SETNX 返回 null（Redis 连接异常等极端情况）
        val convId = "conv-restore-null"
        val uid = 30001L
        val nextSeq = 1L
        val redisKey = "seq:conv:conv-restore-null:next_seq:uid:30001"

        coEvery { redis.setnx(redisKey, "1") } returns null

        // When
        val result = seqService.tryRestoreSeq(convId, uid, nextSeq)

        // Then: ?: false 兜底，保守处理
        assertFalse(result, "SETNX 返回 null 时应兜底返回 false")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Key 格式验证
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun redisKeyFormatCorrect() = runTest {
        // Given: 不同 convId 和 uid 应生成不同的 Key
        coEvery { redis.get(any()) } returns null
        coEvery { redis.incr(any()) } returns 1L

        // When: 调用两个不同会话的 nextSeq
        seqService.nextSeq("abc", 1001L)
        seqService.nextSeq("xyz", 2002L)

        // Then: 验证 Key 格式与独立性
        coVerify { redis.get("seq:conv:abc:next_seq:uid:1001") }
        coVerify { redis.get("seq:conv:xyz:next_seq:uid:2002") }
        coVerify(exactly = 2) { redis.incr(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 已知局限
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * TODO (T06): SeqService Redis 重启恢复集成测试（SUMMARY.md 遗留项）。
     *
     * 以下场景需要嵌入式 Redis 或真实 Redis 环境：
     * 1. nextSeq 在并发 INCR 下保持单调递增
     * 2. tryRestoreSeq 与 nextSeq 的交互（启动时恢复后正常递增）
     * 3. 序列号溢出重置在真实 Redis 中的行为
     *
     * 当前单元测试通过 mock RedisCoroutinesCommands 覆盖了所有代码分支，
     * 但并发语义和 Redis 协议行为仍需集成测试验证。
     */
    // ═══════════════════════════════════════════════════════════════════════
    // recoverSequences — Redis 重启恢复（P1-01）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun recoverSequencesShouldRestoreFromRedis() = runTest {
        val mysqlCount = 42L
        coEvery { redis.setnx(any(), any()) } returns true
        coEvery { redis.expire(any(), any<Long>()) } returns true

        val seq = seqService.recoverSequences(
            conversationSupplier = { listOf(Pair("conv-1", 0)) },
            msgCountByConv = { mysqlCount },
            memberSupplier = { listOf(50001L) }
        )

        assertTrue(seq > 0, "应成功恢复至少一个序列号")
        coVerify(atLeast = 1) { redis.setnx(any(), any()) }
    }

    @Test
    fun recoverSequencesShouldHandleRedisFailure() = runTest {
        coEvery { redis.setnx(any(), any()) } throws RuntimeException("Redis down")

        assertFailsWith<RuntimeException> {
            seqService.recoverSequences(
                conversationSupplier = { listOf(Pair("conv-fail", 0)) },
                msgCountByConv = { 10L },
                memberSupplier = { listOf(50002L) }
            )
        }
    }
}
