package com.nebula.gateway.handler

import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.grpc.Context
import kotlin.coroutines.CoroutineContext

/**
 * 客户端真实 IP 在 CoroutineContext 中的 Key。
 *
 * 由 [com.nebula.gateway.service.ChatService.ChatStreamObserver] 在收到 REQUEST 时，
 * 从 gRPC 传输层已解析的真实客户端 IP（[ClientIpContextKey]，来源为 `ServerCall` 的
 * `TRANSPORT_ATTR_REMOTE_ADDR` 或可信代理的 `x-forwarded-for`）注入协程上下文。
 * Handler 通过 `coroutineContext.requireClientIp()` 获取，用于 IP 地理定位等
 * 服务端自取 IP、客户端不传参的场景。
 *
 * 实现 [CoroutineContext.Element] 以支持通过协程上下文传递（D-03，与 SessionKey 同范式）。
 */
data class ClientIpKey(val ip: String) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ClientIpKey>
}

/**
 * 客户端真实 IP 在 gRPC [Context] 中的 Key（传输层边界解析后写入）。
 *
 * 由 [com.nebula.gateway.interceptor.ClientIpResolver] 在 gRPC `ServerInterceptor` 边界
 * 解析出真实客户端 IP 后，经 `Contexts.interceptCall` 写入 gRPC [Context]；
 * [com.nebula.gateway.service.ChatService.ChatStreamObserver] 在 `onNext` 处理 REQUEST 时
 * 读取并桥接为协程上下文的 [ClientIpKey]。定义在 gateway 模块以便 observer 读取，
 * 避免 server→gateway 的反向依赖。
 */
val ClientIpContextKey: Context.Key<String> = Context.key("nebula.client-ip")

/**
 * 从当前协程上下文获取客户端真实 IP。
 *
 * IP 由传输层在 `ChatStreamObserver.onNext` 中以 `ClientIpKey(ip)` 注入（来源为 gRPC
 * `TRANSPORT_ATTR_REMOTE_ADDR` 或可信代理 `x-forwarded-for`，客户端无法伪造）。
 * 若未注入，抛出 [BizException] 的 SERVICE_UNAVAILABLE。
 *
 * @return 客户端真实 IP 字符串
 * @throws BizException(BizCode.SERVICE_UNAVAILABLE) 若客户端 IP 未注入
 */
fun CoroutineContext.requireClientIp(): String {
    return this[ClientIpKey]?.ip ?: throw BizException(
        BizCode.SERVICE_UNAVAILABLE,
        "无法获取客户端 IP（传输层未解析到真实客户端地址）"
    )
}
