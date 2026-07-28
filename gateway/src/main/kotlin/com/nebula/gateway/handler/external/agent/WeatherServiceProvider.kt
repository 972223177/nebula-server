package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.ServiceDescriptor
import com.nebula.chat.external.WeatherResponse
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WeatherInvoker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 天气服务定义提供者（Phase 10）。
 *
 * 封装 weather 的 [ServiceDescriptor] 描述与调用执行体；执行体统一经 [ExternalServiceOrchestrator.invoke]
 * 路由到 service 模块 [WeatherInvoker]（参数解析/配额/缓存/净化由 Invoker 负责），本类仅负责
 * 把 Invoker 返回的 [WeatherResponse] 转成 result_json（与 output_schema_json 字段集一致）。
 */
class WeatherServiceProvider : ServiceDefinitionProvider {

    override fun create(orchestrator: ExternalServiceOrchestrator): ServiceDefinition {
        val descriptor = ServiceDescriptor.newBuilder()
            .setId(WeatherInvoker.SERVICE_ID)
            .setName("天气查询")
            .setDescription("查询指定城市的实时天气、未来几天预报、空气质量与生活指数。只读、无副作用。")
            .setCategory("weather")
            .setInputSchemaJson(INPUT_SCHEMA)
            .setOutputSchemaJson(OUTPUT_SCHEMA)
            .setRiskLevel("low")
            .setVersion(VERSION)
            .setRequiresUserConsent(false)
            .build()
        return ServiceDefinition(descriptor) { userId, _, paramsJson ->
            val resp = orchestrator.invoke(WeatherInvoker.SERVICE_ID, userId, "", paramsJson) as WeatherResponse
            resultJson(resp)
        }
    }

    /** 由编排层返回的 [WeatherResponse] 构造 result_json（与 OUTPUT_SCHEMA 字段集一致）。 */
    private fun resultJson(resp: WeatherResponse): String =
        buildJsonObject { put("formatted", resp.formatted) }.toString()

    companion object {
        /**
         * 语义版本（集中定义，单一事实来源），可独立演进（如 weather 升 1.1.0 而 web_search 仍 1.0.0）；
         * 后续若要按版本判定调用合法性，以此为准。同时被 [ServiceRegistry.versionHash] 纳入哈希输入。
         */
        const val VERSION = "1.0.0"
        const val INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名，如 北京\"}},\"required\":[\"city\"]}"
        const val OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"formatted\":{\"type\":\"string\",\"description\":\"格式化天气文本\"}}}"
    }
}
