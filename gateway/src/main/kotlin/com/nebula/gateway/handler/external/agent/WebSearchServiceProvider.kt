package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.ServiceDescriptor
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WebSearchInvoker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * 网页搜索服务定义提供者（Phase 10）。
 *
 * 封装 web_search 的 [ServiceDescriptor] 描述与调用执行体；执行体统一经 [ExternalServiceOrchestrator.invoke]
 * 路由到 service 模块 [WebSearchInvoker]（参数解析/白名单校验/配额/缓存/净化由 Invoker 负责），
 * 本类仅负责把 Invoker 返回的 [SearchResponse] 转成 result_json（与 output_schema_json 字段集一致）。
 */
class WebSearchServiceProvider : ServiceDefinitionProvider {

    override fun create(orchestrator: ExternalServiceOrchestrator): ServiceDefinition {
        val descriptor = ServiceDescriptor.newBuilder()
            .setId(WebSearchInvoker.SERVICE_ID)
            .setName("网页搜索")
            .setDescription("通过搜索引擎检索网页或新闻，返回格式化结果与结构化条目。只读、无副作用。")
            .setCategory("web_search")
            .setInputSchemaJson(INPUT_SCHEMA)
            .setOutputSchemaJson(OUTPUT_SCHEMA)
            .setRiskLevel("low")
            .setVersion(VERSION)
            .setRequiresUserConsent(false)
            .build()
        return ServiceDefinition(descriptor) { userId, _, paramsJson ->
            val resp = orchestrator.invoke(WebSearchInvoker.SERVICE_ID, userId, "", paramsJson) as SearchResponse
            resultJson(resp)
        }
    }

    /** 由编排层返回的 [SearchResponse] 构造 result_json（与 OUTPUT_SCHEMA 字段集一致）。 */
    private fun resultJson(resp: SearchResponse): String = buildJsonObject {
        put("formatted", resp.formatted)
        putJsonArray("items") {
            resp.itemsList.forEach { item ->
                addJsonObject {
                    put("title", item.title)
                    put("snippet", item.snippet)
                    put("url", item.url)
                }
            }
        }
    }.toString()

    companion object {
        /**
         * 语义版本（集中定义，单一事实来源），可独立演进；
         * 后续若要按版本判定调用合法性，以此为准。同时被 [ServiceRegistry.versionHash] 纳入哈希输入。
         */
        const val VERSION = "1.0.0"
        const val INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索词\"},\"max_results\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10,\"description\":\"最大结果数，默认5\"},\"search_type\":{\"type\":\"string\",\"enum\":[\"search\",\"news\"],\"description\":\"搜索类型，默认search\"}},\"required\":[\"query\"]}"
        const val OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"formatted\":{\"type\":\"string\",\"description\":\"格式化搜索结果文本\"},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"snippet\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"}}}}}"
    }
}
