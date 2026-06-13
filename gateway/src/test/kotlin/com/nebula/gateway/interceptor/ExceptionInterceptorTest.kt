package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ExceptionInterceptor 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖 D-10 三态异常映射：
 * - BizException → 业务状态码（如 USER_NOT_FOUND → 1200）
 * - IllegalArgumentException → INVALID_PARAM(1000)
 * - 其他未预期 Exception → INTERNAL_ERROR(9000)
 */
class ExceptionInterceptorTest {

    @Test
    fun handleBizException() = runTest {
        val interceptor = ExceptionInterceptor()

        val request = Request.newBuilder().setMethod("user/getProfile").build()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(request) } throws BizException(BizCode.USER_NOT_FOUND)

        val resp = interceptor.intercept(request, mockChain)

        assertEquals(BizCode.USER_NOT_FOUND.code, resp.code)
        assertEquals(BizCode.USER_NOT_FOUND.msg, resp.msg)
        assertEquals("user/getProfile", resp.method)
    }

    @Test
    fun handleIllegalArgumentException() = runTest {
        val interceptor = ExceptionInterceptor()

        val request = Request.newBuilder().setMethod("test.method").build()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(request) } throws IllegalArgumentException("invalid argument")

        val resp = interceptor.intercept(request, mockChain)

        assertEquals(BizCode.INVALID_PARAM.code, resp.code)
        assertEquals("invalid argument", resp.msg)
        assertEquals("test.method", resp.method)
    }

    @Test
    fun handleUnexpectedException() = runTest {
        val interceptor = ExceptionInterceptor()

        val request = Request.newBuilder().setMethod("test.method").build()
        val mockChain = mockk<Interceptor.Chain>()

        coEvery { mockChain.proceed(request) } throws RuntimeException("unexpected error")

        val resp = interceptor.intercept(request, mockChain)

        assertEquals(BizCode.INTERNAL_ERROR.code, resp.code)
        assertEquals(BizCode.INTERNAL_ERROR.msg, resp.msg)
        assertEquals("test.method", resp.method)
    }
}
