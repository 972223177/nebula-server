package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * 限流拦截器 — TokenBucket QPS 限流 + Semaphore 并发限流 双层防护（D-08, C-05）。
 *
 * 职责：
 * - 已认证请求按 userId 限流，每用户最大并发 20 个请求
 * - 未认证请求按来源 IP 限流（当前骨架占位，返回 "unknown"）
 * - 超限请求返回 BizCode.RATE_LIMITED（rate limit exceeded）
 *
 * 双层限流机制：
 * - TokenBucket（C-05）：QPS 限流，容量 200、100/s 补充，先于并发限流快速拒绝突发流量
 * - Semaphore：每用户最大并发 20 个请求，防止协程耗尽
 *
 * 限流阈值（20）和超时时间（100ms）通过常量定义，方便后续配置化。
 *
 * @property permitsPerUser 每用户最大并发请求数
 * @property acquireTimeoutMs 获取信号量的超时时间（毫秒）
 * @property scope 协程作用域，用于后台清理协程；测试可注入控制生命周期
 */
class RateLimitInterceptor(
    private val permitsPerUser: Int = DEFAULT_PERMITS_PER_USER,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    /** C-05: 令牌桶容量（最大突发请求数） */
    private val tokenBucketCapacity: Int = DEFAULT_TOKEN_BUCKET_CAPACITY,
    /** C-05: 令牌桶补充速率（每秒补充的令牌数 = 持续 QPS 上限） */
    private val tokenBucketRefillRate: Double = DEFAULT_TOKEN_BUCKET_REFILL_RATE,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : Interceptor {

    /** 每用户信号量映射 — userId/IP → Semaphore(permitsPerUser) */
    private val userSemaphores = ConcurrentHashMap<String, Semaphore>()

    /** C-05: 每用户令牌桶映射 — userId/IP → TokenBucket */
    private val userTokenBuckets = ConcurrentHashMap<String, TokenBucket>()

    /**
     * 定时清理不活跃的信号量条目（CQ-11）。
     *
     * 每 10 分钟扫描一次，移除所有 permit 已全部归还的条目，
     * 防止长期运行中 userSemaphores 因 IP 变化或过期用户无限增长导致 OOM。
     * 清理协程运行在注入的 [scope] 上，默认使用 Dispatchers.IO（daemon 线程池）。
     *
     * 调用 [shutdown] 可取消清理循环（如测试结束后释放资源）。
     */
    private val cleanupJob: Job = scope.launch {
        while (isActive) {
            try {
                delay(CLEANUP_INTERVAL_MS)
                // 清理空闲信号量
                val beforeSem = userSemaphores.size
                userSemaphores.entries.removeIf { it.value.availablePermits() == permitsPerUser }
                // C-05 + H1: 清理空闲令牌桶（非 suspend，无 runBlocking）
                val beforeBucket = userTokenBuckets.size
                userTokenBuckets.entries.removeIf {
                    it.value.isIdleForCleanup(CLEANUP_INTERVAL_MS)
                }
                val afterSem = userSemaphores.size
                val afterBucket = userTokenBuckets.size
                if (beforeSem != afterSem || beforeBucket != afterBucket) {
                    log.debug { "RateLimiter 清理: 信号量 $beforeSem→$afterSem, 令牌桶 $beforeBucket→$afterBucket" }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                log.warn(e) { "RateLimiter 清理异常" }
            }
        }
    }

    /**
     * 关闭清理协程，释放资源。
     *
     * 调用后清理循环停止，[userSemaphores] 不再被扫描。
     * 适用于测试场景或应用优雅关闭时使用。
     */
    fun shutdown() {
        cleanupJob.cancel()
    }

    /** 注册 IP 限流器 — 每小时每 IP 最多 5 次注册（D-02） */
    private val registerLimiter = RegisterRateLimiter()

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        // 注册请求走独立 IP 限流（D-02）
        if (request.method == "user/register") {
            val ip = extractClientIp(request)
            if (!registerLimiter.tryAcquire(ip)) {
                log.warn { "Register rate limit exceeded for ip=$ip" }
                return Response.newBuilder()
                    .setCode(BizCode.RATE_LIMITED.code)
                    .setMsg("register rate limit exceeded")
                    .build()
            }
        }

        // 获取限流 key：已认证请求使用 userId，未认证请求使用 IP
        val session = currentCoroutineContext()[SessionKey]
        val limitKey = session?.session?.userId?.toString() ?: extractClientIp(request)

        // C-05: 令牌桶 QPS 限流（先于并发限流，快速拒绝突发流量）
        val tokenBucket = userTokenBuckets.computeIfAbsent(limitKey) {
            TokenBucket(tokenBucketCapacity, tokenBucketRefillRate)
        }
        if (!tokenBucket.tryAcquire()) {
            log.warn { "QPS rate limit exceeded for key=$limitKey, method=${request.method}" }
            return Response.newBuilder()
                .setCode(BizCode.RATE_LIMITED.code)
                .setMsg(RATE_LIMITED_MSG)
                .build()
        }

        // 获取或创建该用户的信号量（并发数限流）
        val semaphore = userSemaphores.computeIfAbsent(limitKey) { Semaphore(permitsPerUser) }

        // 尝试获取信号量，超时未获取到则限流
        val acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)
        if (!acquired) {
            log.warn { "Rate limit exceeded for key=$limitKey, method=${request.method}" }
            return Response.newBuilder()
                .setCode(BizCode.RATE_LIMITED.code)
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
         * C-02/C-09 修复：所有对 [ipRequestTimes] 的读写（含 getOrPut）必须在 [mutex] 内执行，
         * 避免两个并发协程为同一 IP 创建不同 MutableList 实例导致限流计数丢失。
         * 清理空条目也在锁内完成，避免 remove(ip, times) 的 TOCTOU 竞态。
         *
         * @param ip 客户端 IP
         * @return true=允许注册，false=超出限流
         */
        suspend fun tryAcquire(ip: String): Boolean {
            val now = System.currentTimeMillis()
            return mutex.withLock {
                val times = ipRequestTimes.getOrPut(ip) { mutableListOf() }
                times.removeAll { now - it > windowMs }
                val acquired = if (times.size >= maxRequests) {
                    false
                } else {
                    times.add(now)
                    true
                }
                // 内存泄漏防护：清除已过期的空 IP 条目（锁内执行，无竞态）
                if (times.isEmpty()) {
                    ipRequestTimes.remove(ip)
                }
                acquired
            }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** 每用户最大并发请求数 */
        private const val DEFAULT_PERMITS_PER_USER = 20

        /** 获取信号量的超时时间（毫秒） */
        private const val DEFAULT_ACQUIRE_TIMEOUT_MS = 100L

        /** 限流响应消息 */
        private const val RATE_LIMITED_MSG = "rate limit exceeded"

        /** 信号量清理间隔（毫秒），每 10 分钟扫描一次不活跃条目（CQ-11） */
        private const val CLEANUP_INTERVAL_MS = 10 * 60 * 1000L

        /** C-05: 令牌桶默认容量（最大突发请求数）。
         * 200 足以容纳重连补偿场景下的并发请求（登录+拉好友请求+拉消息+拉会话等多个接口同时发出） */
        private const val DEFAULT_TOKEN_BUCKET_CAPACITY = 200

        /** C-05: 令牌桶默认补充速率（每秒 100 个令牌 = 100 QPS 持续上限） */
        private const val DEFAULT_TOKEN_BUCKET_REFILL_RATE = 100.0
    }
}
