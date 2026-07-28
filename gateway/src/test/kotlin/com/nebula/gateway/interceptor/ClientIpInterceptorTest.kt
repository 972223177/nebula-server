package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.handler.ClientIpKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ClientIpInterceptor 单元测试。
 *
 * 覆盖场景：
 * - 优先从 x-client-ip 提取并注入 ClientIpKey
 * - 缺失 x-client-ip 时回退 x-forwarded-for
 * - 两者皆缺时注入 "unknown"
 */
class ClientIpInterceptorTest {

    private val interceptor = ClientIpInterceptor()

    private fun chainCapturingIp(req: Request): Interceptor.Chain {
        return object : Interceptor.Chain {
            override val request: Request get() = req
            override suspend fun proceed(request: Request): Response {
                // 在 ClientIpInterceptor 注入的 withContext(ClientIpKey) 内执行，可读取到客户端 IP
                lastSeenIp = coroutineContext[ClientIpKey]?.ip
                return Response.getDefaultInstance()
            }
        }
    }

    private var lastSeenIp: String? = null

    @Test
    fun injectsClientIpFromMetadata() = runTest {
        val req = mockk<Request> { every { metadataMap } returns mapOf("x-client-ip" to "1.2.3.4") }
        interceptor.intercept(req, chainCapturingIp(req))
        assertEquals("1.2.3.4", lastSeenIp)
    }

    @Test
    fun fallsBackToXForwardedFor() = runTest {
        val req = mockk<Request> { every { metadataMap } returns mapOf("x-forwarded-for" to "5.6.7.8") }
        interceptor.intercept(req, chainCapturingIp(req))
        assertEquals("5.6.7.8", lastSeenIp)
    }

    @Test
    fun unknownWhenBothMissing() = runTest {
        val req = mockk<Request> { every { metadataMap } returns emptyMap() }
        interceptor.intercept(req, chainCapturingIp(req))
        assertEquals("unknown", lastSeenIp)
    }
}
