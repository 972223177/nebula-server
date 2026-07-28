package com.nebula.gateway.handler.external
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.external.IpLocationRequest
import com.nebula.chat.external.IpLocationResponse
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireClientIp
import com.nebula.gateway.handler.requireSession
import com.nebula.service.external.ExternalServiceOrchestrator
import kotlinx.coroutines.currentCoroutineContext

/**
 * IP 地理定位 Handler（D-XX）—— method = "external/geo_ip"。
 *
 * 仅做协议适配：从 CoroutineContext 取登录态 userId 与客户端真实 IP
 * （[requireClientIp]，由 ClientIpInterceptor 从连接元数据提取，客户端不传参），
 * 委托 [ExternalServiceOrchestrator] 完成配额/缓存/上游编排。业务异常由
 * ExceptionInterceptor 统一捕获并写入 Response.code/msg。
 *
 * @param orchestrator 外部服务编排层
 */
class IpLocationHandler(
    private val orchestrator: ExternalServiceOrchestrator
) : Handler<IpLocationRequest, IpLocationResponse> {

    /** 路由方法名 */
    override val method: String = MethodNames.External.GEO_IP

    /**
     * 处理 IP 定位请求。
     *
     * @param req 反序列化后的 IpLocationRequest（故意为空，IP 由服务端提取）
     * @return 定位结果封装的 IpLocationResponse
     */
    override suspend fun handle(req: IpLocationRequest): IpLocationResponse {
        val userId = currentCoroutineContext().requireSession().userId
        val clientIp = currentCoroutineContext().requireClientIp()
        return orchestrator.locateByIp(userId, clientIp)
    }
}
