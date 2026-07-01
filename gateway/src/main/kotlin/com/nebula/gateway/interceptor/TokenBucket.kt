package com.nebula.gateway.interceptor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 令牌桶限流器 — 基于 Mutex 的协程友好实现（C-05 修复）。
 *
 * 与 [RateLimitInterceptor] 中基于 Semaphore 的并发限流互补：
 * - Semaphore 限制每用户最大并发请求数（防止协程耗尽）
 * - TokenBucket 限制每用户 QPS（防止突发流量冲击后端）
 *
 * 算法：
 * - 桶容量 [capacity] 决定最大突发量
 * - 令牌以 [refillRatePerSecond] 的速率匀速补充
 * - 每次请求消耗 1 个令牌，不足时拒绝
 *
 * 线程安全：通过 [Mutex] 保证协程安全，不使用 Java 锁原语。
 *
 * @param capacity 桶容量（最大突发请求数）
 * @param refillRatePerSecond 令牌补充速率（每秒补充的令牌数）
 */
class TokenBucket(
    private val capacity: Int,
    private val refillRatePerSecond: Double
) {
    /** 协程互斥锁，保证令牌计算的原子性 */
    private val mutex = Mutex()

    /** 当前令牌数 */
    private var tokens: Double = capacity.toDouble()

    /** 上次补充令牌的时间戳（毫秒） */
    private var lastRefillTimeMs: Long = System.currentTimeMillis()

    /**
     * H1+M5 修复：最近一次成功获取令牌的时间戳。
     * 使用 @Volatile 保证协程写入对清理线程的可见性。
     * 用于非 suspend 的空闲检测，消除清理任务中的 runBlocking。
     */
    @Volatile
    var lastAcquireTimeMs: Long = System.currentTimeMillis()
        private set

    /**
     * 尝试获取 1 个令牌。
     *
     * 先按时间差补充令牌（不超过容量上限），再尝试消耗。
     *
     * @return true 获取成功，false 令牌不足
     */
    suspend fun tryAcquire(): Boolean {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsedSeconds = (now - lastRefillTimeMs) / 1000.0
            // 补充令牌，不超过容量上限
            tokens = minOf(capacity.toDouble(), tokens + elapsedSeconds * refillRatePerSecond)
            lastRefillTimeMs = now
            // 尝试消耗
            if (tokens >= 1.0) {
                tokens -= 1.0
                lastAcquireTimeMs = now
                true
            } else {
                false
            }
        }
    }

    /**
     * H1+M5 修复：非 suspend 空闲检测，用于清理任务。
     *
     * 基于最近访问时间戳判断，无需获取 Mutex，避免清理循环中的 runBlocking。
     * 无需精确 token 数 — 长时间未请求的桶即可安全清理（下次重建成本极低）。
     *
     * @param intervalMs 空闲超时间隔（毫秒），默认 10min
     * @return true 如果超过指定时间未使用此桶
     */
    fun isIdleForCleanup(intervalMs: Long): Boolean {
        return System.currentTimeMillis() - lastAcquireTimeMs >= intervalMs
    }
}
