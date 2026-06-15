package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 全链路集成测试 — 精简版（D-24, D-26）。
 *
 * 保留核心 Pipeline 验证：
 * - Phase 4: system/ping（无认证）
 * - test/authenticated（需认证注入 Session）
 *
 * Phase 5 用户 Handler 分发测试（login/register/search/getProfile）已移至对应 HandlerTest 中覆盖。
 */
class PipelineIntegrationTest {

    /**
     * 需要认证的 Mock Handler，用于验证 AuthInterceptor 注入 Session 后 Handler 可正确获取。
     * method = "test/authenticated"
     */
    class MockAuthenticatedHandler : Handler<Request, Response> {
        override val method: String = "test/authenticated"

        override suspend fun handle(req: Request): Response {
            val session = currentCoroutineContext().requireSession()
            return Response.newBuilder()
                .setCode(200)
                .setMsg("authenticated: ${session.userId}")
                .setMethod(method)
                .build()
        }
    }

    @Test
    fun fullPipelineProcessesPingRequest() = runTest {
        // 准备 HandlerRegistry 并注册 PingHandler
        val registry = HandlerRegistry()
        val pingHandler = PingHandler()
        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        registry.register(
            HandlerEntry(
                handler = pingHandler,
                reqClass = Request::class,
                respClass = Response::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // 构建 Interceptor Pipeline — 手动构造（D-07 顺序）
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptors = listOf(
            AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")),
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        // 执行 PingHandler 请求
        val request = Request.newBuilder().setMethod("system/ping").build()
        val response = dispatcher.dispatch(request)

        // 验证：ping 请求应返回 200
        // Dispatcher 将 Handler 返回值序列化到 response.result 中，外层 Response code=200
        assertEquals(200, response.code, "ping response code should be 200")

        // 反序列化 result bytes 验证内层 Response 包含 "pong"
        val innerResponse = Response.parseFrom(response.result.toByteArray())
        assertEquals("pong", innerResponse.msg, "inner response msg should be pong")
    }

    @Test
    fun authenticatedHandlerReceivesSessionViaAuthInterceptor() = runTest {
        // 准备 HandlerRegistry 并注册 MockAuthenticatedHandler
        val registry = HandlerRegistry()
        val handler = MockAuthenticatedHandler()
        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        registry.register(
            HandlerEntry(
                handler = handler,
                reqClass = Request::class,
                respClass = Response::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock SessionRegistry — validate 返回固定 Session
        val sessionRegistry = mockk<SessionRegistry>()
        val testSession = Session(
            userId = 1L,
            token = "test-token",
            deviceType = "test",
            deviceId = "dev1",
            connectionId = "conn1"
        )
        coEvery { sessionRegistry.validate("test-token") } returns testSession

        // 自定义 AuthInterceptor，覆盖 extractToken 返回固定 token
        val customAuthInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping")
        ) {
            override fun extractToken(request: Request): String? = "test-token"
        }

        // 构建 Interceptor Pipeline
        val interceptors = listOf(
            customAuthInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        // 执行需认证的请求
        val request = Request.newBuilder().setMethod("test/authenticated").build()
        val response = dispatcher.dispatch(request)

        // 验证：认证通过，外层 Response code=200
        assertEquals(200, response.code, "authenticated response code should be 200")

        // 反序列化 result bytes 验证内层 Response 包含 userId from Session
        val innerResponse = Response.parseFrom(response.result.toByteArray())
        assertEquals("authenticated: 1", innerResponse.msg, "inner response msg should contain userId from Session")

        // 验证 SessionRegistry.validate() 被调用（AuthInterceptor 实际执行了认证）
        coVerify(exactly = 1) { sessionRegistry.validate("test-token") }
    }
}
