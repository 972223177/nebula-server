package com.nebula.common.idgen

/**
 * 时钟抽象接口（CQ-14, T01）。
 *
 * 用于 SnowflakeIdGenerator 的时钟回拨检测，生产环境使用 [SystemClock]，
 * 测试环境使用 FakeClock 注入可控时钟，替代反射操作 private 字段。
 */
interface Clock {
    /** 返回当前时间戳（毫秒） */
    fun millis(): Long
}

/**
 * 系统时钟实现 — 委托给 [System.currentTimeMillis]。
 */
class SystemClock : Clock {
    override fun millis(): Long = System.currentTimeMillis()
}
