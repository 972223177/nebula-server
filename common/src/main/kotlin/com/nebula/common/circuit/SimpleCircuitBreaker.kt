package com.nebula.common.circuit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 简单熔断器 — 基于 Mutex 的协程友好实现（C-07 修复）。
 *
 * 三态模型：
 * - CLOSED：正常放行，记录连续失败数。失败数达到 [failureThreshold] 后跳转 OPEN。
 * - OPEN：熔断中，所有请求立即拒绝（返回 false）。经过 [resetTimeoutMs] 后跳转 HALF_OPEN。
 * - HALF_OPEN：放行单个探测请求。成功 → CLOSED，失败 → OPEN。
 *
 * 线程安全：通过 [Mutex] 保证协程安全，不使用 Java 锁原语。
 *
 * 典型用法：
 * ```kotlin
 * if (!circuitBreaker.allowRequest()) {
 *     return null // 熔断中，快速失败
 * }
 * try {
 *     val result = redisCall()
 *     circuitBreaker.recordSuccess()
 *     result
 * } catch (e: Exception) {
 *     circuitBreaker.recordFailure()
 *     null
 * }
 * ```
 *
 * @param failureThreshold 连续失败次数阈值，触发熔断
 * @param resetTimeoutMs 熔断恢复等待时间（毫秒），超时后进入 HALF_OPEN
 */
class SimpleCircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val resetTimeoutMs: Long = DEFAULT_RESET_TIMEOUT_MS
) {
    /** 协程互斥锁 */
    private val mutex = Mutex()

    /** 当前熔断状态 */
    private var state: State = State.CLOSED

    /** 连续失败计数（CLOSED 状态下递增，成功时归零） */
    private var failureCount: Int = 0

    /** 熔断打开时间戳（OPEN 状态下记录，用于判断是否进入 HALF_OPEN） */
    private var openedAtMs: Long = 0L

    /**
     * 检查是否允许请求通过。
     *
     * CLOSED → 始终允许。
     * OPEN → 检查是否已过 [resetTimeoutMs]，是则切换 HALF_OPEN 并允许探测请求，否则拒绝。
     * HALF_OPEN → 拒绝（仅允许一个探测请求，已由 OPEN→HALF_OPEN 转换时放行）。
     *
     * @return true 允许通过，false 熔断中应快速失败
     */
    suspend fun allowRequest(): Boolean {
        return mutex.withLock {
            when (state) {
                State.CLOSED -> true
                State.OPEN -> {
                    val elapsed = System.currentTimeMillis() - openedAtMs
                    if (elapsed >= resetTimeoutMs) {
                        // 恢复窗口，切换到 HALF_OPEN，放行单个探测请求
                        state = State.HALF_OPEN
                        true
                    } else {
                        false
                    }
                }
                State.HALF_OPEN -> {
                    // 已有探测请求在执行，拒绝后续请求
                    false
                }
            }
        }
    }

    /**
     * 记录请求成功。
     *
     * CLOSED → 重置失败计数。
     * HALF_OPEN → 切换到 CLOSED，恢复服务。
     */
    suspend fun recordSuccess() {
        mutex.withLock {
            failureCount = 0
            if (state == State.HALF_OPEN) {
                state = State.CLOSED
            }
        }
    }

    /**
     * 记录请求失败。
     *
     * CLOSED → 递增失败计数，达到阈值则切换 OPEN。
     * HALF_OPEN → 切换回 OPEN。
     */
    suspend fun recordFailure() {
        mutex.withLock {
            when (state) {
                State.CLOSED -> {
                    failureCount++
                    if (failureCount >= failureThreshold) {
                        state = State.OPEN
                        openedAtMs = System.currentTimeMillis()
                    }
                }
                State.HALF_OPEN -> {
                    state = State.OPEN
                    openedAtMs = System.currentTimeMillis()
                }
                State.OPEN -> { /* 已熔断，无需处理 */ }
            }
        }
    }

    /** 熔断状态 */
    private enum class State {
        CLOSED, OPEN, HALF_OPEN
    }

    companion object {
        /** 默认连续失败阈值 */
        private const val DEFAULT_FAILURE_THRESHOLD = 5

        /** 默认熔断恢复等待时间（10s） */
        private const val DEFAULT_RESET_TIMEOUT_MS = 10_000L
    }
}
