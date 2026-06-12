package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * LogInterceptor 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 正常请求返回原始的响应对象
 */
class LogInterceptorTest {

    @Test
    fun `log success request`() = runTest {
        val interceptor = LogInterceptor()

        val request = Request.newBuilder().setMethod("test.method").build()
        val mockChain = mockk<Interceptor.Chain>()
        val successResponse = Response.newBuilder()
            .setCode(200)
            .setMethod("test.method")
            .build()

        coEvery { mockChain.proceed(request) } returns successResponse

        val resp = interceptor.intercept(request, mockChain)

        assertEquals(200, resp.code)
        assertEquals("test.method", resp.method)
    }
}
