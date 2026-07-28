package com.nebula.gateway.handler.external
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.external.IpLocationRequest
import com.nebula.chat.external.IpLocationResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireClientIp
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.interceptor.isPublicUnicastIpv4
import com.nebula.service.external.ExternalServiceOrchestrator
import kotlinx.coroutines.currentCoroutineContext

/**
 * IP 地理定位 Handler（D-XX）—— method = "external/geo_ip"。
 *
 * 仅做协议适配：从 CoroutineContext 取登录态 userId 与客户端真实 IP
 * （[requireClientIp]，由传输层 ClientIpServerInterceptor 解析真实客户端 IP 注入，
 *  客户端无法伪造、无需传参），
 * 并在委托 [ExternalServiceOrchestrator] 前做本地短路：非公网 IPv4（私有/回环/链路本地/
 * 保留段等）高德无法定位，直接抛 [BizException]（SERVICE_UNAVAILABLE）而**不发起**上游调用，
 * 避免对注定查不到的 IP 浪费配额。业务异常由 ExceptionInterceptor 统一捕获并写入
 * Response.code/msg。
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
        // 本地短路：非公网 IPv4（私有/回环/链路本地/保留段）高德必然查不到，
        // 直接降级而不发起上游调用，省去无意义的上游配额消耗（D-XX）
        if (!isPublicUnicastIpv4(clientIp)) {
            throw BizException(
                BizCode.SERVICE_UNAVAILABLE,
                "非公网 IP 无法地理定位（本地已短路，不调用上游）: $clientIp"
            )
        }
        return orchestrator.locateByIp(userId, clientIp)
    }
}
