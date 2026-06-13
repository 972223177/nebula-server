package com.nebula.common.idgen

import com.nebula.common.exception.ClockBackwardsException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SnowflakeIdGenerator] 的单元测试。
 *
 * 测试策略：
 * - 构造函数校验 workerId 范围（无需模拟时钟）
 * - 基本功能测试依赖真实系统时间，验证 ID 结构正确
 * - 时钟回拨场景通过反射操作内部 [SnowflakeIdGenerator.lastTimestamp] 字段模拟
 *
 * 该生成器使用 [System.currentTimeMillis] 作为时钟源，其返回值为 private 函数
 * 不可覆盖，因此时钟回拨测试通过反射绕过此限制。
 */
class SnowflakeIdGeneratorTest {

    /** 测试用 workerId */
    private val testWorkerId = 1L

    // ─── 1. 构造函数 workerId 校验 ────────────────────────────────────────────────

    @Test
    fun constructorShouldAcceptWorkerId0() {
        val generator = SnowflakeIdGenerator(workerId = 0)
        assertEquals(0, generator.workerId)
    }

    @Test
    fun constructorShouldAcceptWorkerId1023() {
        val generator = SnowflakeIdGenerator(workerId = 1023)
        assertEquals(1023, generator.workerId)
    }

    @Test
    fun constructorShouldRejectWorkerIdMinus1() {
        val ex = assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(workerId = -1)
        }
        assertTrue(ex.message?.contains("-1") == true, "Exception message should contain the invalid workerId")
    }

    @Test
    fun constructorShouldRejectWorkerId1024() {
        val ex = assertThrows<IllegalArgumentException> {
            SnowflakeIdGenerator(workerId = 1024)
        }
        assertTrue(ex.message?.contains("1024") == true, "Exception message should contain the invalid workerId")
    }

    // ─── 2. nextId() 基本功能 ────────────────────────────────────────────────────

    @Test
    fun nextIdShouldReturnAPositiveId() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)
        val id = generator.nextId()
        assertTrue(id > 0, "Generated ID should be positive, got $id")
    }

    @Test
    fun nextIdShouldEncodeWorkerIdAndSequenceCorrectly() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)
        val id = generator.nextId()

        // 提取 workerId（bits 21-12）和 sequence（bits 11-0）
        val extractedWorkerId = (id shr 12) and 0x3FF
        val extractedSequence = id and 0xFFF

        assertEquals(testWorkerId, extractedWorkerId, "WorkerId component should match")
        assertEquals(0L, extractedSequence, "Sequence should start at 0 on first call")
    }

    // ─── 3. 序列号递增 ──────────────────────────────────────────────────────────

    @Test
    fun sequenceShouldIncrementWithinTheSameMillisecond() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)

        // 连续快速调用，期望在同一毫秒内序列号递增
        val id1 = generator.nextId()
        val id2 = generator.nextId()

        val seq1 = id1 and 0xFFF
        val seq2 = id2 and 0xFFF

        // 如果两次调用落在同一毫秒，seq2 应大于 seq1
        // 若跨毫秒（概率极低，但不为零），seq2 可能回到 0
        // 这里测试的是 seq2 != seq1（即序列号有意义的变化）
        assertTrue(seq2 != seq1, "Consecutive calls should have different sequences (got $seq1, $seq2)")
    }

    @Test
    fun sequenceShouldResetTo0InANewMillisecond() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)

        // 生成一个 ID 建立上下文，然后等待下一毫秒再生成下一个
        generator.nextId()

        // 忙等待直到下一毫秒
        val currentTs = System.currentTimeMillis()
        while (System.currentTimeMillis() <= currentTs) {
            // 自旋等待时钟前进
        }

        val id2 = generator.nextId()
        val seq2 = id2 and 0xFFF

        // 在新的毫秒中序列号应回到 0
        assertEquals(0L, seq2, "Sequence should reset to 0 in a new millisecond")
    }

    // ─── 4. 时钟回拨 ─────────────────────────────────────────────────────────────

    @Test
    fun clockMovingBackwardsShouldThrowClockBackwardsException() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)

        // 通过反射将内部 lastTimestamp 设为未来值，使 nextId 检测到"时钟回拨"
        val lastTimestampField = SnowflakeIdGenerator::class.java.getDeclaredField("lastTimestamp")
        lastTimestampField.isAccessible = true
        val futureTime = System.currentTimeMillis() + 10_000L
        lastTimestampField.setLong(generator, futureTime)

        val ex = assertThrows<ClockBackwardsException> {
            generator.nextId()
        }
        assertTrue(
            ex.message?.contains("backwards") == true,
            "Exception message should contain 'backwards', got: ${ex.message}"
        )
    }

    // ─── 5. ID 唯一性 ────────────────────────────────────────────────────────────

    @Test
    fun multipleCallsShouldProduceUniqueIds() = runTest {
        val generator = SnowflakeIdGenerator(workerId = testWorkerId)
        val ids = mutableSetOf<Long>()

        repeat(1_000) {
            ids.add(generator.nextId())
        }

        assertEquals(1_000, ids.size, "All 1000 generated IDs should be unique")
    }

    @Test
    fun differentWorkerIdsShouldProduceDifferentIds() = runTest {
        val generator1 = SnowflakeIdGenerator(workerId = 1)
        val generator2 = SnowflakeIdGenerator(workerId = 2)

        val ids1 = (1..100).map { generator1.nextId() }.toSet()
        val ids2 = (1..100).map { generator2.nextId() }.toSet()

        // 各 worker 内部唯一
        assertEquals(100, ids1.size, "workerId=1 should produce 100 unique IDs")
        assertEquals(100, ids2.size, "workerId=2 should produce 100 unique IDs")

        // 两个 worker 之间无交叉
        assertTrue(ids1.intersect(ids2).isEmpty(), "IDs from different workers should not overlap")
    }
}
