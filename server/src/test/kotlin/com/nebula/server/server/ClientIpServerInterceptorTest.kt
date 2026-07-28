package com.nebula.server.server

import io.grpc.Context
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import com.nebula.gateway.handler.ClientIpContextKey
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * ClientIpServerInterceptor 单元测试 —— 验证传输层边界解析并写入 gRPC Context。
 */
class ClientIpServerInterceptorTest {

    @Test
    fun interceptCallWritesResolvedTransportIpIntoContext() {
        val addr = InetSocketAddress("203.0.113.9", 54321)
        val attributes = io.grpc.Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
            .build()

        val call = mockk<ServerCall<Any, Any>>(relaxed = true)
        every { call.attributes } returns attributes

        val captured = AtomicReference<String?>(null)
        val next = ServerCallHandler<Any, Any> { _: ServerCall<Any, Any>, _: Metadata ->
            object : ServerCall.Listener<Any>() {
                override fun onMessage(message: Any?) {
                    // 回调应在 interceptor 注入的 Context 内执行；gRPC 读取约定为 Key.get(Context)
                    captured.set(ClientIpContextKey.get(Context.current()))
                }
            }
        }

        val interceptor = ClientIpServerInterceptor(emptyList())
        val listener = interceptor.interceptCall(call, Metadata(), next)
        listener.onMessage(Any())

        assertEquals("203.0.113.9", captured.get())
    }

    @Test
    fun interceptCallIgnoresSpoofedXffWhenNoTrustedProxy() {
        val addr = InetSocketAddress("203.0.113.9", 54321)
        val attributes = io.grpc.Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
            .build()

        val call = mockk<ServerCall<Any, Any>>(relaxed = true)
        every { call.attributes } returns attributes

        val headers = Metadata()
        headers.put(Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER), "9.9.9.9")

        val captured = AtomicReference<String?>(null)
        val next = ServerCallHandler<Any, Any> { _: ServerCall<Any, Any>, _: Metadata ->
            object : ServerCall.Listener<Any>() {
                override fun onMessage(message: Any?) {
                    captured.set(ClientIpContextKey.get(Context.current()))
                }
            }
        }

        val interceptor = ClientIpServerInterceptor(emptyList())
        interceptor.interceptCall(call, headers, next).onMessage(Any())

        assertEquals("203.0.113.9", captured.get())
    }
}
