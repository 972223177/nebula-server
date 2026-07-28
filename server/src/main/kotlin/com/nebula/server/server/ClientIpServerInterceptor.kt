package com.nebula.server.server

import com.nebula.gateway.handler.ClientIpContextKey
import com.nebula.gateway.interceptor.ClientIpResolver
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.SocketAddress

/**
 * 客户端真实 IP 注入拦截器（传输层，D-XX）—— 在 gRPC 连接建立时从传输层解析真实客户端 IP
 * 并写入 gRPC [Context]，供业务层（[com.nebula.gateway.handler.ClientIpKey]）读取。
 *
 * 位置与职责：
 * - 注册于 [ChatServer.start] 的 gRPC `ServerInterceptor` 链（per-stream `interceptCall`）。
 * - 唯一可信 IP 来源：优先取 `ServerCall.attributes[Grpc.TRANSPORT_ATTR_REMOTE_ADDR]`
 *   （Netty 传输层真实 socket 对端，客户端无法伪造），结合可信代理网段判定是否信任
 *   `x-forwarded-for`（详见 [ClientIpResolver]）。
 * - 经 `Contexts.interceptCall` 把解析结果写入 gRPC [Context]，使后续 `onMessage` 回调
 *   （[com.nebula.gateway.service.ChatService.ChatStreamObserver.onNext]）能读取并桥接为
 *   协程上下文的 [com.nebula.gateway.handler.ClientIpKey]。
 *
 * 与 [com.nebula.server.server.ChatServer.debugInterceptor] 分工：本拦截器解析"真实客户端 IP"
 * （可能经代理修正），`debugInterceptor` 仅记录原始传输层对端地址（诊断用），互不替代。
 *
 * @param trustedProxies 可信代理网段 CIDR 列表（来自 `server.trusted-proxies` 配置）
 */
class ClientIpServerInterceptor(
    private val trustedProxies: List<String>
) : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val remoteAddr: SocketAddress? = call.attributes[Grpc.TRANSPORT_ATTR_REMOTE_ADDR]
        val clientIp = ClientIpResolver.resolve(remoteAddr, headers, trustedProxies)
        logger.debug { "[client-ip] 解析真实客户端 IP=$clientIp remoteAddr=$remoteAddr trustedProxies=$trustedProxies" }

        val context: Context = Context.current().withValue(ClientIpContextKey, clientIp)
        return Contexts.interceptCall(context, call, headers, next)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
