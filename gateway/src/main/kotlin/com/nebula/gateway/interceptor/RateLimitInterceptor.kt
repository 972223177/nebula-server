package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.handler.SessionKey
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.coroutineContext
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

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        // 获取限流 key：已认证请求使用 userId，未认证请求使用 IP
        val session = coroutineContext[SessionKey]
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
     * 从请求中提取客户端 IP。
     *
     * TODO: Phase 5 确定请求来源 IP 的传递方式后实现，当前骨架占位返回 "unknown"。
     *
     * @param request 客户端请求
     * @return 客户端 IP 字符串
     */
    private fun extractClientIp(request: Request): String = "unknown"

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
