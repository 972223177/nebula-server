package com.nebula.gateway.handler

import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import kotlin.coroutines.CoroutineContext

/**
 * 客户端真实 IP 在 CoroutineContext 中的 Key。
 *
 * 由 [com.nebula.gateway.interceptor.ClientIpInterceptor] 在收到请求时从连接元数据
 * （x-client-ip / x-forwarded-for）提取并注入协程上下文，Handler 通过
 * `coroutineContext.requireClientIp()` 获取，用于 IP 地理定位等无需客户端传参、服务端自取 IP 的场景。
 *
 * 实现 [CoroutineContext.Element] 以支持通过协程上下文传递（D-03，与 SessionKey 同范式）。
 */
data class ClientIpKey(val ip: String) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ClientIpKey>
}

/**
 * 从当前协程上下文获取客户端真实 IP。
 *
 * 由 ClientIpInterceptor 在 `withContext(ClientIpKey(ip))` 中注入。
 * 若未注入（连接元数据缺失且未走 ClientIpInterceptor），抛出 [BizException] 的 SERVICE_UNAVAILABLE。
 *
 * @return 客户端真实 IP 字符串
 * @throws BizException(BizCode.SERVICE_UNAVAILABLE) 若客户端 IP 未注入
 */
fun CoroutineContext.requireClientIp(): String {
    return this[ClientIpKey]?.ip ?: throw BizException(
        BizCode.SERVICE_UNAVAILABLE,
        "无法获取客户端 IP（连接元数据缺失 x-client-ip / x-forwarded-for）"
    )
}
