package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.handler.SessionKey
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 限流拦截器 — 基于 Semaphore 的每用户并发限流骨架（D-08）。
 *
 * 职责：
 * - 已认证请求按 userId 限流，每用户最大并发 20 个请求
 * - 未认证请求按来源 IP 限流（当前骨架占位，返回 "unknown"）
 * - 超限请求返回 429（rate limit exceeded）
 *
 * 当前阶段（Phase 4）实现为基于 Semaphore 的简单并发限流，提供基础保护。
 * TODO: Phase 11 替换为令牌桶算法（如 Bucket4j），支持更精细的速率限制。
 *
 * 限流阈值（20）和超时时间（100ms）通过常量定义，方便后续配置化。
 *
 * @property permitsPerUser 每用户最大并发请求数
 * @property acquireTimeoutMs 获取信号量的超时时间（毫秒）
 */
class RateLimitInterceptor(
    private val permitsPerUser: Int = DEFAULT_PERMITS_PER_USER,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS
) : Interceptor {

    /** 每用户信号量映射 — userId/IP → Semaphore(permitsPerUser) */
    private val userSemaphores = ConcurrentHashMap<String, Semaphore>()

    /** 注册 IP 限流器 — 每小时每 IP 最多 5 次注册（D-02） */
    private val registerLimiter = RegisterRateLimiter()

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        // 注册请求走独立 IP 限流（D-02）
        if (request.method == "user/register") {
            val ip = extractClientIp(request)
            if (!registerLimiter.tryAcquire(ip)) {
                log.warn { "Register rate limit exceeded for ip=$ip" }
                return Response.newBuilder()
                    .setCode(RATE_LIMITED_CODE)
                    .setMsg("register rate limit exceeded")
                    .build()
            }
        }

        // 获取限流 key：已认证请求使用 userId，未认证请求使用 IP
        val session = currentCoroutineContext()[SessionKey]
        val limitKey = session?.session?.userId?.toString() ?: extractClientIp(request)

        // 获取或创建该用户的信号量
        val semaphore = userSemaphores.computeIfAbsent(limitKey) { Semaphore(permitsPerUser) }

        // 尝试获取信号量，超时未获取到则限流
        val acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)
        if (!acquired) {
            log.warn { "Rate limit exceeded for key=$limitKey, method=${request.method}" }
            return Response.newBuilder()
                .setCode(RATE_LIMITED_CODE)
                .setMsg(RATE_LIMITED_MSG)
                .build()
        }

        return try {
            chain.proceed(request)
        } finally {
            semaphore.release()
        }
    }

    /**
     * 从请求的 metadata map 中提取客户端 IP。
     *
     * IP 优先级：x-client-ip → x-forwarded-for → "unknown"
     *
     * @param request 客户端请求
     * @return 客户端 IP 字符串
     */
    private fun extractClientIp(request: Request): String {
        return request.metadataMap["x-client-ip"]
            ?: request.metadataMap["x-forwarded-for"]
            ?: "unknown"
    }

    /**
     * 注册 IP 限流器 — 每小时每 IP 最多 5 次注册（D-02）。
     *
     * 内存泄漏防护：tryAcquire() 每次调用后检查并移除空 IP 条目。
     * 避免恶意 IP 变换导致 ipRequestTimes 无限膨胀。
     */
    class RegisterRateLimiter {
        private val ipRequestTimes = ConcurrentHashMap<String, MutableList<Long>>()
        private val maxRequests = 5
        private val windowMs = 60 * 60 * 1000L  // 1 小时

        /** 协程互斥锁，替代 synchronized 以避免协程线程阻塞 */
        private val mutex = Mutex()

        /**
         * 尝试获取注册许可。
         *
         * @param ip 客户端 IP
         * @return true=允许注册，false=超出限流
         */
        suspend fun tryAcquire(ip: String): Boolean {
            val now = System.currentTimeMillis()
            val times = ipRequestTimes.getOrPut(ip) { mutableListOf() }
            val acquired = mutex.withLock {
                times.removeAll { now - it > windowMs }
                if (times.size >= maxRequests) false else {
                    times.add(now)
                    true
                }
            }
            // 内存泄漏修复：清除已过期空的 IP 条目（Review 反馈#5）
            if (times.isEmpty()) {
                ipRequestTimes.remove(ip, times)
            }
            return acquired
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** 每用户最大并发请求数 */
        private const val DEFAULT_PERMITS_PER_USER = 20

        /** 获取信号量的超时时间（毫秒） */
        private const val DEFAULT_ACQUIRE_TIMEOUT_MS = 100L

        /** 限流响应状态码 */
        private const val RATE_LIMITED_CODE = 429

        /** 限流响应消息 */
        private const val RATE_LIMITED_MSG = "rate limit exceeded"
    }
}
