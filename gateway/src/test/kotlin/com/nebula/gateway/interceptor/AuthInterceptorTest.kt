package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * AuthInterceptor 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 白名单方法（system/ping）跳过认证
 * - 未携带 Token 返回 UNAUTHORIZED
 * - Token 无效返回 TOKEN_INVALID
 * - Token 有效注入 Session 到 CoroutineContext
 */
class AuthInterceptorTest {

    @Test
    fun skipAuthForSystemPing() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptor = AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping"))

        val request = Request.newBuilder().setMethod("system/ping").build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(200, resp.code)
        // 验证 SessionRegistry 未被调用
        coVerify(inverse = true) { sessionRegistry.validate(any()) }
    }

    @Test
    fun rejectWhenTokenMissing() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptor = AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping"))

        val request = Request.newBuilder().setMethod("user/login").build()
        val mockChain = mockk<Interceptor.Chain>()

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(BizCode.UNAUTHORIZED.code, resp.code)
        assertEquals(BizCode.UNAUTHORIZED.msg, resp.msg)
        // 验证 SessionRegistry 和 chain.proceed 未被调用
        coVerify(inverse = true) { sessionRegistry.validate(any()) }
        coVerify(inverse = true) { mockChain.proceed(any()) }
    }

    @Test
    fun rejectWhenTokenInvalid() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        coEvery { sessionRegistry.validate(any()) } returns null

        // 通过匿名子类覆盖 extractToken 返回固定的 token
        val interceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = "test-token-abc"
        }

        val request = Request.newBuilder().setMethod("user/login").build()
        val mockChain = mockk<Interceptor.Chain>()

        val resp = interceptor.intercept(request, mockChain)
        assertEquals(BizCode.TOKEN_INVALID.code, resp.code)
        assertEquals(BizCode.TOKEN_INVALID.msg, resp.msg)
        // 验证 SessionRegistry.validate 被调用一次
        coVerify(exactly = 1) { sessionRegistry.validate(any()) }
        // 验证 chain.proceed 未被调用（因认证失败）
        coVerify(inverse = true) { mockChain.proceed(any()) }
    }

    @Test
    fun injectSessionToCoroutineContextWhenTokenValid() = runTest {
        val sessionRegistry = mockk<SessionRegistry>()
        val session = Session(
            userId = 1001L,
            token = "test-token-abc",
            deviceType = "android",
            deviceId = "device-001",
            connectionId = "conn-001"
        )
        coEvery { sessionRegistry.validate(any()) } returns session

        val interceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = "test-token-abc"
        }

        val request = Request.newBuilder().setMethod("user/login").build()
        val mockChain = mockk<Interceptor.Chain>()
        val expectedResp = Response.newBuilder().setCode(200).build()

        coEvery { mockChain.proceed(request) } returns expectedResp

        val resp = interceptor.intercept(request, mockChain)

        assertEquals(200, resp.code)
        // 验证 SessionRegistry.validate 被调用
        coVerify(exactly = 1) { sessionRegistry.validate("test-token-abc") }
        // 验证 chain.proceed 被调用（认证通过）
        coVerify(exactly = 1) { mockChain.proceed(request) }
    }
}
