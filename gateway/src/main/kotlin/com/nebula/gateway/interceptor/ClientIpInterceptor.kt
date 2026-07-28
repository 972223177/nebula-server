package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.handler.ClientIpKey
import kotlinx.coroutines.withContext

/**
 * 客户端 IP 注入拦截器（D-XX）—— 从连接元数据提取客户端真实 IP 并注入协程上下文。
 *
 * 职责：
 * - 从 [Request.metadataMap] 按优先级 `x-client-ip → x-forwarded-for` 提取客户端 IP，
 *   与 [RateLimitInterceptor] 的提取逻辑保持一致（后者用于未认证请求的限流 key）。
 * - 通过 `withContext(ClientIpKey(ip))` 将 IP 注入协程上下文，下游 Handler 经
 *   `coroutineContext.requireClientIp()` 获取，用于 IP 地理定位等"服务端自取 IP、客户端不传参"的场景。
 * - 与 [com.nebula.gateway.handler.SessionKey] 同范式：关注点分离，不污染 Handler 接口签名。
 *
 * 注意：x-client-ip 为客户端/反向代理/LB 注入的**可信边界值**（部署时由网关校验），
 * 不可由客户端任意伪造用于定位——本拦截器仅负责透传提取，定位结果仅作展示，不影响鉴权。
 */
class ClientIpInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val ip = request.metadataMap["x-client-ip"]
            ?: request.metadataMap["x-forwarded-for"]
            ?: "unknown"
        return withContext(ClientIpKey(ip)) {
            chain.proceed(request)
        }
    }
}
