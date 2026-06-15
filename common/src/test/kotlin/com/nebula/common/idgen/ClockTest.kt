package com.nebula.common.idgen

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Clock] 接口及其实现的单元测试。
 *
 * 测试策略：
 * - [SystemClock] 委托给 System.currentTimeMillis()，验证误差在合理范围内
 * - [FakeClock] 作为测试替身，验证可控时钟注入模式
 * - 界面测试验证接口契约：调用 millis() 返回非负值
 */
class ClockTest {

    // ─── SystemClock — 委托 System.currentTimeMillis() ────────────────────────────

    @Test
    fun `SystemClock millis — 返回值大于 0`() {
        val clock: Clock = SystemClock()
        assertTrue(clock.millis() > 0, "时钟应返回正数毫秒值")
    }

    @Test
    fun `SystemClock millis — 与 System currentTimeMillis 误差小于 50ms`() {
        val clock = SystemClock()
        val before = System.currentTimeMillis()
        val millis = clock.millis()
        val after = System.currentTimeMillis()

        // 读取时刻应在 [before, after] 区间内，允许微小偏差
        assertTrue(
            millis in before..after,
            "clock.millis()=$millis 应落在 [before=$before, after=$after] 区间内"
        )
        assertTrue(
            after - before < 50,
            "连续调用时间差应小于 50ms，实际=${after - before}ms"
        )
    }

    @Test
    fun `SystemClock millis — 单调非递减`() {
        val clock = SystemClock()
        val first = clock.millis()
        val second = clock.millis()
        assertTrue(second >= first, "millis() 应单调非递减: first=$first, second=$second")
    }

    // ─── FakeClock — 测试替身注入 ────────────────────────────────────────────────

    /**
     * 测试用 FakeClock，允许精确控制返回值。
     */
    private class FakeClock(private val fixedMillis: Long) : Clock {
        override fun millis(): Long = fixedMillis
    }

    @Test
    fun `FakeClock — 返回注入的固定值`() {
        val fixedTime = 1700000000000L
        val clock = FakeClock(fixedTime)
        assertEquals(fixedTime, clock.millis())
    }

    @Test
    fun `FakeClock — 连续调用返回相同值`() {
        val fixedTime = 1700000000000L
        val clock = FakeClock(fixedTime)
        assertEquals(clock.millis(), clock.millis())
    }

    // ─── 接口契约 — 面向 Clock 接口编程 ───────────────────────────────────────────

    @Test
    fun `Clock 多态 — SystemClock 和 FakeClock 均可赋值给 Clock 引用`() {
        val systemClock: Clock = SystemClock()
        val fakeClock: Clock = FakeClock(0L)

        assertTrue(systemClock.millis() > 0)
        assertEquals(0L, fakeClock.millis())
    }
}
