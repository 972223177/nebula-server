package com.nebula.gateway.handler.external
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.external.SearchRequest
import com.nebula.chat.external.SearchResponse
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WebSearchInvoker
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 网页搜索 Handler（D-XX）—— method = "external/web_search"。
 *
 * 仅做协议适配：从 CoroutineContext 取登录态 userId（规范 9，禁止重新解析 token），
 * 将 Req 转为统一 params_json 后委托 [ExternalServiceOrchestrator.invoke] 完成协议无关的外部服务调用。
 * 业务异常由 ExceptionInterceptor 统一捕获并写入 Response.code/msg（见 external-service-backend.md §2/§4.3）。
 *
 * @param orchestrator 外部服务编排入口
 */
class WebSearchHandler(
    private val orchestrator: ExternalServiceOrchestrator
) : Handler<SearchRequest, SearchResponse> {

    /** 路由方法名 */
    override val method: String = MethodNames.External.WEB_SEARCH

    /**
     * 处理网页搜索请求。
     *
     * @param req 反序列化后的 SearchRequest（query / maxResults / searchType）
     * @return 格式化搜索结果封装的 SearchResponse
     */
    override suspend fun handle(req: SearchRequest): SearchResponse {
        val userId = currentCoroutineContext().requireSession().userId
        val paramsJson = buildJsonObject {
            put("query", req.query)
            put("max_results", req.maxResults)
            put("search_type", req.searchType)
        }.toString()
        return orchestrator.invoke(WebSearchInvoker.SERVICE_ID, userId, "", paramsJson) as SearchResponse
    }
}
