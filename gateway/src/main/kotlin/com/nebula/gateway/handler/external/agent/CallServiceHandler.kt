package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.CallServiceRequest
import com.nebula.chat.external.CallServiceResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.external.MissingParamException
import com.nebula.common.external.ParamIssue
import com.nebula.gateway.handler.ClientIpKey
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.MethodNames
import com.nebula.gateway.handler.requireSession
import kotlinx.coroutines.currentCoroutineContext

/**
 * 通用服务调用 Handler（D-XX，Phase 10）—— method = "external/call_service"。
 *
 * 协议闭环关键：客户端无需为每个服务写死调用，只需按 `service_id` + `params_json` 路由到
 * [ServiceRegistry] 中对应的执行体，结果以通用 `result_json` 返回。现有 3 个外部服务
 * （天气/搜索/定位）经此入口即可被 LLM 自主调用，且自动复用既有配额/缓存/鉴权/净化机制。
 *
 * 错误语义（复用 BizCode，与 Dispatcher 既有映射一致）：
 * - 未知 `service_id` → [BizCode.NOT_FOUND]；
 * - `params_json` 缺失必填项或类型非法 → 不报错，而是在 [CallServiceResponse.paramIssues] 中以
 *   结构化方式告知前端（[com.nebula.common.external.MissingParamException] 由执行体抛出，
 *   此处捕获后保持成功码，弱模型场景下前端可程序化识别"需引导用户补参"）。
 *
 * @param registry 外部服务注册表
 */
class CallServiceHandler(
    private val registry: ServiceRegistry
) : Handler<CallServiceRequest, CallServiceResponse> {

    /** 路由方法名 */
    override val method: String = MethodNames.External.CALL_SERVICE

    /**
     * 处理通用服务调用请求。
     *
     * @param req 含 service_id 与 params_json
     * @return 含 result_json 的响应
     * @throws BizException NOT_FOUND（未知 service_id）/ INVALID_PARAM（参数非法）/ SERVICE_UNAVAILABLE（上游）
     */
    override suspend fun handle(req: CallServiceRequest): CallServiceResponse {
        val userId = currentCoroutineContext().requireSession().userId
        // 非抛出方式取 clientIp：天气/搜索等 IP 无关服务不应因取不到 IP 而失败；
        // 缺失时回落 "unknown"，由 geo_ip 执行体的公网短路统一降级为 SERVICE_UNAVAILABLE（见审查发现 3）
        val clientIp = currentCoroutineContext()[ClientIpKey]?.ip ?: "unknown"
        // service_id 统一小写，免疫 LLM 工具调用的大小写漂移（如 Web_Search）→ NOT_FOUND（见审查发现 2）
        val def = registry.get(req.serviceId.lowercase())
            ?: throw BizException(BizCode.NOT_FOUND, "未知 service_id: ${req.serviceId}")
        // 参数约束未满足（缺参/类型错/空值）由执行体抛出 MissingParamException：保持外层成功码，
        // 仅在 param_issues 中结构化告知前端"需引导用户补参"，弱模型场景下前端可程序化识别。
        val (resultJson, issues) = try {
            def.invoke(userId, clientIp, req.paramsJson) to emptyList<ParamIssue>()
        } catch (e: MissingParamException) {
            // 缺参时不再调用上游、不计配额；result_json 回落为空对象，约束信息全在 param_issues
            "{}" to e.issues
        }
        return CallServiceResponse.newBuilder()
            .setResultJson(resultJson)
            .addAllParamIssues(issues.map {
                com.nebula.chat.external.ParamIssue.newBuilder()
                    .setParam(it.param)
                    .setReason(it.reason)
                    .setHint(it.hint)
                    .build()
            })
            .build()
    }
}
