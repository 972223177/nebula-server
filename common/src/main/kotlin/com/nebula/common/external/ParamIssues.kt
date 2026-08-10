package com.nebula.common.external

/**
 * 单个参数约束问题（缺参/类型错/空值等），供 external/call_service 响应以结构化方式告知前端，
 * 让前端（即便搭载弱模型无法自行追问用户）也能程序化识别并引导用户补全参数。
 *
 * @property param 出问题的参数名，如 "city" / "query" / "search_type"
 * @property reason 问题类型：missing（缺省未提供）/ empty（空串）/ invalid_type（类型不符）/ invalid_value（值不合法）
 * @property hint 给前端的引导文案，如 "请指定城市名，如 北京"
 */
data class ParamIssue(
    val param: String,
    val reason: String,
    val hint: String,
)

/**
 * 参数约束未满足时抛出的专用异常。**非** [com.nebula.common.exception.BizException] 子类，
 * 故 [com.nebula.gateway.interceptor.ExceptionInterceptor] 不会将其映射为错误码（如 INVALID_PARAM），
 * 而是由 [com.nebula.gateway.handler.external.agent.CallServiceHandler] 捕获后保持外层 Response.code = 0，
 * 并将 issues 填入 CallServiceResponse.param_issues，形成"成功码 + 结构化约束信号"的弱模型友好形态。
 *
 * @property issues 本次调用缺失/非法的参数清单
 */
class MissingParamException(val issues: List<ParamIssue>) : Exception(
    issues.joinToString("; ") { "${it.param}:${it.reason}" }
)
