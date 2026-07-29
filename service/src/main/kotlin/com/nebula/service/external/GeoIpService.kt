package com.nebula.service.external

/**
 * IP 地理定位聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [GeoIpServiceImpl]（实现 [GeoIpOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler 经 `get<GeoIpService>()` 获取，零改动。
 *
 * 定位结果类型 [GeoIpResult] 保留为本类嵌套类型，以维持 `GeoIpService.GeoIpResult` 既有外部引用
 * （[com.nebula.service.external.GeoIpInvokerTest]）。
 */
class GeoIpService(impl: GeoIpServiceImpl) : GeoIpOperations by impl {
    /** 定位结果（结构化字段 + 合并格式化文本） */
    data class GeoIpResult(
        val country: String,
        val region: String,
        val city: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
        val isp: String,
        val formatted: String
    )
}
