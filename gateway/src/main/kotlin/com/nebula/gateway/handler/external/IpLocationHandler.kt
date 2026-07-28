package com.nebula.gateway.handler.external
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.external.IpLocationRequest
import com.nebula.chat.external.IpLocationResponse
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireClientIp
import com.nebula.gateway.handler.requireSession
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.GeoIpInvoker
import kotlinx.coroutines.currentCoroutineContext

/**
 * IP 地理定位 Handler（D-XX）—— method = "external/geo_ip"。
 *
 * 仅做协议适配：从 CoroutineContext 取登录态 userId 与客户端真实 IP
 * （[requireClientIp]，由传输层 ClientIpServerInterceptor 解析真实客户端 IP 注入，
 *  客户端无法伪造、无需传参），委托 [ExternalServiceOrchestrator.invoke] 完成调用。
 * 非公网 IP 的本地短路（isPublicUnicastIpv4）已下沉到 service 模块 [GeoIpInvoker]，
 * 本 Handler 不再重复该逻辑。业务异常由 ExceptionInterceptor 统一捕获并写入 Response.code/msg。
 *
 * @param orchestrator 外部服务编排入口
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
        return orchestrator.invoke(GeoIpInvoker.SERVICE_ID, userId, clientIp, "{}") as IpLocationResponse
    }
}
