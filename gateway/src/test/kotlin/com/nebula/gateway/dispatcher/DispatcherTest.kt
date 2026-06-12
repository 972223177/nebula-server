package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.interceptor.Interceptor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Dispatcher 全链路单元测试。
 *
 * 覆盖（D-26）：
 * - 成功路径：找到 Handler → 反序列化 → Pipeline → 序列化 → Response
 * - method 不存在返回 NOT_FOUND (1003)
 * - 无拦截器时直接调用 Handler
 * - 有拦截器时 Pipeline 正常执行
 */
class DispatcherTest {

    @Test
    fun `dispatch with valid handler returns response`() = runTest {
        val handler = mockk<Handler<Any, Any>>()
        every { handler.method } returns "test.method"
        coEvery { handler.handle(any()) } returns Response.newBuilder().setCode(200).build()

        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        val entry = HandlerEntry(
            handler = handler,
            reqClass = Request::class,
            respClass = Response::class,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )

        val handlerRegistry = mockk<HandlerRegistry>()
        every { handlerRegistry.get("test.method") } returns entry

        val dispatcher = Dispatcher(handlerRegistry, emptyList())

        val request = Request.newBuilder().setMethod("test.method").build()
        val response = dispatcher.dispatch(request)

        assertEquals(BizCode.OK.code, response.code)
        assertEquals("test.method", response.method)
        coVerify(exactly = 1) { handler.handle(any()) }
    }

    @Test
    fun `dispatch with unknown method returns NOT_FOUND`() = runTest {
        val handlerRegistry = mockk<HandlerRegistry>()
        every { handlerRegistry.get("unknown.method") } returns null

        val dispatcher = Dispatcher(handlerRegistry, emptyList())

        val request = Request.newBuilder().setMethod("unknown.method").build()
        val response = dispatcher.dispatch(request)

        assertEquals(BizCode.NOT_FOUND.code, response.code)
    }

    @Test
    fun `dispatch with empty interceptors still works`() = runTest {
        val handler = mockk<Handler<Any, Any>>()
        every { handler.method } returns "test.method"
        coEvery { handler.handle(any()) } returns Response.newBuilder().setCode(200).build()

        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        val entry = HandlerEntry(
            handler = handler,
            reqClass = Request::class,
            respClass = Response::class,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )

        val handlerRegistry = mockk<HandlerRegistry>()
        every { handlerRegistry.get("test.method") } returns entry

        val dispatcher = Dispatcher(handlerRegistry, emptyList())

        val request = Request.newBuilder().setMethod("test.method").build()
        val response = dispatcher.dispatch(request)

        assertEquals(BizCode.OK.code, response.code)
        coVerify(exactly = 1) { handler.handle(any()) }
    }

    @Test
    fun `dispatch with interceptors invokes pipeline`() = runTest {
        val handler = mockk<Handler<Any, Any>>()
        every { handler.method } returns "test.method"
        coEvery { handler.handle(any()) } returns Request.getDefaultInstance()

        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Request::class)
        val entry = HandlerEntry(
            handler = handler,
            reqClass = Request::class,
            respClass = Request::class,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )

        val handlerRegistry = mockk<HandlerRegistry>()
        every { handlerRegistry.get("test.method") } returns entry

        val interceptor = mockk<Interceptor>()
        coEvery { interceptor.intercept(any(), any()) } returns Response.newBuilder()
            .setCode(200)
            .setMethod("test.method")
            .build()

        val dispatcher = Dispatcher(handlerRegistry, listOf(interceptor))

        val request = Request.newBuilder().setMethod("test.method").build()
        val response = dispatcher.dispatch(request)

        assertEquals(200, response.code)
        coVerify(exactly = 1) { interceptor.intercept(any(), any()) }
        coVerify(inverse = true) { handler.handle(any()) }
    }
}
