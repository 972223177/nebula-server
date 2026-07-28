package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.IpLocationResponse
import com.nebula.chat.external.ServiceDescriptor
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.GeoIpInvoker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * IP 地理定位服务定义提供者（Phase 10）。
 *
 * 封装 geo_ip 的 [ServiceDescriptor] 描述与调用执行体；不接收客户端传入 IP，
 * 由执行体从注入的 [clientIp] 提取。非公网 IP 的本地短路（复用 isPublicUnicastIpv4）已下沉到
 * service 模块 [GeoIpInvoker]，本类仅把 Invoker 返回的 [IpLocationResponse] 转成
 * result_json（与 output_schema_json 字段集一致）。
 */
class GeoIpServiceProvider : ServiceDefinitionProvider {

    override fun create(orchestrator: ExternalServiceOrchestrator): ServiceDefinition {
        val descriptor = ServiceDescriptor.newBuilder()
            .setId(GeoIpInvoker.SERVICE_ID)
            .setName("IP 地理定位")
            .setDescription(
                "根据客户端真实 IP 解析地理位置（国家/省/市/经纬度/时区/运营商）。" +
                    "不接收客户端传入的 IP，由服务端从连接元数据提取，避免伪造。只读、无副作用。"
            )
            .setCategory("utility")
            .setInputSchemaJson(INPUT_SCHEMA)
            .setOutputSchemaJson(OUTPUT_SCHEMA)
            .setRiskLevel("low")
            .setVersion(VERSION)
            .setRequiresUserConsent(false)
            .build()
        return ServiceDefinition(descriptor) { userId, clientIp, _ ->
            val resp = orchestrator.invoke(GeoIpInvoker.SERVICE_ID, userId, clientIp, "{}") as IpLocationResponse
            resultJson(resp)
        }
    }

    /** 由编排层返回的 [IpLocationResponse] 构造 result_json（与 OUTPUT_SCHEMA 字段集一致）。 */
    private fun resultJson(resp: IpLocationResponse): String = buildJsonObject {
        put("formatted", resp.formatted)
        put("country", resp.country)
        put("region", resp.region)
        put("city", resp.city)
        put("latitude", resp.latitude)
        put("longitude", resp.longitude)
        put("timezone", resp.timezone)
        put("isp", resp.isp)
    }.toString()

    companion object {
        /**
         * 语义版本（集中定义，单一事实来源），可独立演进；
         * 后续若要按版本判定调用合法性，以此为准。同时被 [ServiceRegistry.versionHash] 纳入哈希输入。
         */
        const val VERSION = "1.0.0"
        const val INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        const val OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"formatted\":{\"type\":\"string\"},\"country\":{\"type\":\"string\"},\"region\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"},\"latitude\":{\"type\":\"number\"},\"longitude\":{\"type\":\"number\"},\"timezone\":{\"type\":\"string\"},\"isp\":{\"type\":\"string\"}}}"
    }
}
