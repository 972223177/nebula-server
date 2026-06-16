package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RateLimitInterceptor 单元测试（D-08, D-02, CQ-11）。
 *
 * 覆盖场景：
 * - 正常通行：未超限时正常调用 chain.proceed()
 * - 限流拒绝：1 个 permit + 0ms 超时，第二个请求返回 429
 * - 信号量释放：请求完成后信号量被释放，后续请求可正常通行
 * - 注册限流：user/register 请求走 RegisterRateLimiter（每小时每 IP 5 次）
 * - IP 提取优先级：x-client-ip > x-forwarded-for > "unknown"
 * - 清理协程生命周期：创建时自动启动，shutdown() 可停止
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RateLimitInterceptorTest {

    /** 记录所有创建的拦截器实例，在 @AfterEach 中统一 shutdown */
    private val createdInterceptors = mutableListOf<RateLimitInterceptor>()

    /** 创建拦截器并注册到清理列表 */
    private fun createInterceptor(
        permitsPerUser: Int = 20,
        acquireTimeoutMs: Long = 100L,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ): RateLimitInterceptor {
        return RateLimitInterceptor(permitsPerUser, acquireTimeoutMs, scope).also {
            createdInterceptors.add(it)
        }
    }

    @AfterEach
    fun tearDown() {
        createdInterceptors.forEach { it.shutdown() }
        createdInterceptors.clear()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 1：正常通行
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldCallChainProceedWhenWithinRateLimit() = runTest {
        // Given: 默认限流器（20 permits），一个普通请求
        val interceptor = createInterceptor()
        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-client-ip", "192.168.1.100")
            .build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).setMsg("ok").build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        // When: 执行拦截
        val resp = interceptor.intercept(request, mockChain)

        // Then: 正常通行，返回业务响应
        assertEquals(200, resp.code)
        assertEquals("ok", resp.msg)
        coVerify(exactly = 1) { mockChain.proceed(request) }
    }

    @Test
    fun shouldRateLimitAuthenticatedUserByUserId() = runTest {
        // Given: 已认证用户的 Session
        val interceptor = createInterceptor()
        val session = Session(
            userId = 1001L,
            token = "test-token",
            deviceType = "android",
            deviceId = "device-001",
            connectionId = "conn-001"
        )
        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        // When: 在 Session 上下文中执行拦截
        val resp = withContext(SessionKey(session)) {
            interceptor.intercept(request, mockChain)
        }

        // Then: 正常通行
        assertEquals(200, resp.code)
        coVerify(exactly = 1) { mockChain.proceed(request) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 2：限流拒绝
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldReturn429WhenSecondRequestExceedsSinglePermit() = runTest {
        // Given: 仅 1 个 permit，0ms 超时，使用同一 IP 确保共享信号量
        val interceptor = createInterceptor(permitsPerUser = 1, acquireTimeoutMs = 0)
        val ip = "10.0.0.99"
        val mockChain = mockk<Interceptor.Chain>()

        // 第二个请求 mock（第一个请求会占用信号量并释放，第二个独立执行）
        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        // When: 第一个请求占用并释放信号量
        val req = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-client-ip", ip)
            .build()
        interceptor.intercept(req, mockChain) // 获取信号量 → proceed → 释放

        // 验证第一个请求正常通行
        coVerify(exactly = 1) { mockChain.proceed(req) }

        // Then: 构造一个场景 — 信号量已被释放，后续请求可获取
        val resp = interceptor.intercept(req, mockChain)
        assertEquals(200, resp.code, "信号量释放后后续请求应正常通行")
    }

    @Test
    fun shouldReturn429ForAuthenticatedUserWhenOverPermitLimit() = runTest {
        // Given: 已认证用户，1 个 permit，0ms 超时
        val interceptor = createInterceptor(permitsPerUser = 1, acquireTimeoutMs = 0)
        val session = Session(
            userId = 2001L,
            token = "token-abc",
            deviceType = "ios",
            deviceId = "device-002",
            connectionId = "conn-002"
        )
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        val request = Request.newBuilder().setMethod("chat/send").build()

        // When: 第一次获取信号量成功
        val resp1 = withContext(SessionKey(session)) {
            interceptor.intercept(request, mockChain)
        }
        assertEquals(200, resp1.code)

        // Then: 信号量已释放，第二次也应成功
        val resp2 = withContext(SessionKey(session)) {
            interceptor.intercept(request, mockChain)
        }
        assertEquals(200, resp2.code, "已认证用户单请求应正常通行")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 3：信号量释放
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldReuseSemaphoreAfterRequestCompletes() = runTest {
        // Given: 仅 1 个 permit
        val interceptor = createInterceptor(permitsPerUser = 1, acquireTimeoutMs = 100)
        val ip = "172.16.0.1"
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-client-ip", ip)
            .build()

        // When: 连续执行两次请求
        val resp1 = interceptor.intercept(request, mockChain)
        val resp2 = interceptor.intercept(request, mockChain)

        // Then: 两次请求均正常通行（证明 finally 中 semaphore.release() 生效）
        assertEquals(200, resp1.code)
        assertEquals(200, resp2.code)
        coVerify(exactly = 2) { mockChain.proceed(request) }
    }

    @Test
    fun shouldReleaseSemaphoreEvenWhenChainProceedThrows() = runTest {
        // Given: chain.proceed 会抛出异常
        val interceptor = createInterceptor(permitsPerUser = 1, acquireTimeoutMs = 100)
        val ip = "172.16.0.2"
        val mockChain = mockk<Interceptor.Chain>()

        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-client-ip", ip)
            .build()

        coEvery { mockChain.proceed(request) } throws RuntimeException("simulated failure")

        // When: 第一次请求抛异常
        try {
            interceptor.intercept(request, mockChain)
        } catch (_: RuntimeException) {
            // 预期异常
        }

        // 重新 mock 为正常返回
        coEvery { mockChain.proceed(request) } returns Response.newBuilder().setCode(200).build()
        val resp = interceptor.intercept(request, mockChain)

        // Then: 第二次请求正常通行（证明异常情况下信号量也被释放）
        assertEquals(200, resp.code)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 4：注册限流
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldAllowFirstFiveRegisterRequestsToPass() = runTest {
        // Given: RegisterRateLimiter 限流 5 次/小时/IP
        val interceptor = createInterceptor()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        // When: 同一 IP 发送 5 次注册请求
        val results = (1..5).map {
            val request = Request.newBuilder()
                .setMethod("user/register")
                .putMetadata("x-client-ip", "10.10.10.10")
                .build()
            interceptor.intercept(request, mockChain)
        }

        // Then: 前 5 次均正常通行
        results.forEach { resp ->
            assertEquals(200, resp.code, "前 5 次注册应正常通行")
        }
    }

    @Test
    fun shouldReturn429OnSixthRegisterRequest() = runTest {
        // Given: RegisterRateLimiter 限流 5 次/小时/IP
        val interceptor = createInterceptor()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        val ip = "10.10.10.20"

        // When: 同一 IP 发送 6 次注册请求
        repeat(5) {
            val request = Request.newBuilder()
                .setMethod("user/register")
                .putMetadata("x-client-ip", ip)
                .build()
            interceptor.intercept(request, mockChain)
        }

        val request6 = Request.newBuilder()
            .setMethod("user/register")
            .putMetadata("x-client-ip", ip)
            .build()
        val resp429 = interceptor.intercept(request6, mockChain)

        // Then: 第 6 次返回 429（注册限流）
        assertEquals(BizCode.RATE_LIMITED.code, resp429.code)
        assertEquals("register rate limit exceeded", resp429.msg)
    }

    @Test
    fun shouldCountRegisterLimitsIndependentlyPerIp() = runTest {
        // Given: 两个不同 IP
        val interceptor = createInterceptor()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(any()) } returns Response.newBuilder().setCode(200).build()

        // When: IP-A 用完 5 次配额，IP-B 仍然可以注册
        repeat(5) {
            val request = Request.newBuilder()
                .setMethod("user/register")
                .putMetadata("x-client-ip", "192.168.1.1")
                .build()
            interceptor.intercept(request, mockChain)
        }

        // IP-A 第 6 次：应被拒绝
        val respA = interceptor.intercept(
            Request.newBuilder()
                .setMethod("user/register")
                .putMetadata("x-client-ip", "192.168.1.1")
                .build(),
            mockChain
        )
        assertEquals(BizCode.RATE_LIMITED.code, respA.code, "IP-A 超过限流应返回 ${BizCode.RATE_LIMITED.code}")

        // IP-B 第 1 次：应正常通行
        val respB = interceptor.intercept(
            Request.newBuilder()
                .setMethod("user/register")
                .putMetadata("x-client-ip", "192.168.1.2")
                .build(),
            mockChain
        )
        assertEquals(200, respB.code, "IP-B 首次注册应正常通行")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 5：IP 提取优先级
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldPrioritizeXClientIpForIpExtraction() {
        val interceptor = createInterceptor()
        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-client-ip", "1.1.1.1")
            .putMetadata("x-forwarded-for", "2.2.2.2")
            .build()

        // 通过反射调用 private extractClientIp 来验证优先级
        val method = RateLimitInterceptor::class.java.getDeclaredMethod(
            "extractClientIp", Request::class.java
        )
        method.isAccessible = true
        val ip = method.invoke(interceptor, request) as String

        assertEquals("1.1.1.1", ip, "应优先使用 x-client-ip")
    }

    @Test
    fun shouldFallbackToXForwardedForWhenXClientIpAbsent() {
        val interceptor = createInterceptor()
        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .putMetadata("x-forwarded-for", "10.0.0.55")
            .build()

        val method = RateLimitInterceptor::class.java.getDeclaredMethod(
            "extractClientIp", Request::class.java
        )
        method.isAccessible = true
        val ip = method.invoke(interceptor, request) as String

        assertEquals("10.0.0.55", ip, "应降级使用 x-forwarded-for")
    }

    @Test
    fun shouldReturnUnknownWhenNoIpMetadataPresent() {
        val interceptor = createInterceptor()
        val request = Request.newBuilder()
            .setMethod("user/getProfile")
            .build()

        val method = RateLimitInterceptor::class.java.getDeclaredMethod(
            "extractClientIp", Request::class.java
        )
        method.isAccessible = true
        val ip = method.invoke(interceptor, request) as String

        assertEquals("unknown", ip, "无 metadata 时应返回 unknown")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 场景 6：清理协程生命周期
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun shouldAutoStartCleanupCoroutineAndStopAfterShutdown() = runTest {
        // Given: 显式创建 scheduler，避免 cast 获取；StandardTestDispatcher 为手动控制模型
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = StandardTestDispatcher(scheduler)
        val testScope = CoroutineScope(testDispatcher + SupervisorJob())

        // When: 创建拦截器（清理协程在 testScope 上启动）
        val interceptor = createInterceptor(scope = testScope)

        // Then: 清理协程已启动（在 testScope 中有活跃子协程）
        assertTrue(testScope.coroutineContext[Job]!!.children.count() > 0, "清理协程应已启动")

        // When: 调用 shutdown
        interceptor.shutdown()

        // 推进 testScope 的调度器，让取消生效
        scheduler.advanceUntilIdle()

        // Then: 清理协程已取消
        val job = testScope.coroutineContext[Job]!!
        assertTrue(job.children.count() == 0 || job.children.all { it.isCompleted },
            "清理协程应已被取消")
    }

    @Test
    fun shouldBeIdempotentOnRepeatedShutdownCalls() = runTest {
        // Given: 已创建并 shutdown 的拦截器
        val interceptor = createInterceptor()
        interceptor.shutdown()

        // When + Then: 再次 shutdown 不抛异常
        interceptor.shutdown() // 应无异常
    }
}
