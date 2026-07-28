package com.nebula.service.external

import com.nebula.chat.external.IpLocationResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.net.isPublicUnicastIpv4

/**
 * IP 地理定位服务执行体（D-XX）—— 封装 geo_ip 的参数（IP 来自连接元数据）/ 本地短路 /
 * 配额 / 缓存 / 上游调用 / 净化。
 *
 * 复用 [ExternalServicePipeline] 统一管线；x-forwarded-for 多级代理取首个 IP，并在调用上游前
 * 复用 [isPublicUnicastIpv4] 本地短路（非公网 IP 高德必然查不到，直接降级而不浪费配额），
 * 与旧 [com.nebula.gateway.handler.external.IpLocationHandler] 行为一致。
 *
 * @param pipeline 通用编排管线
 * @param geoIpService 高德 IP 定位服务
 */
class GeoIpInvoker(
    private val pipeline: ExternalServicePipeline,
    private val geoIpService: GeoIpService
) : ExternalServiceInvoker<IpLocationResponse> {

    override val serviceId: String = SERVICE_ID

    override suspend fun invoke(userId: Long, clientIp: String, paramsJson: String): IpLocationResponse {
        // 规范化客户端 IP：x-forwarded-for 可能为 "ip, proxy1, proxy2"，取首个最原始客户端 IP
        val ip = normalizeIp(clientIp)
        // 本地短路：非公网 IPv4（私有/回环/链路本地/保留段等）高德无法定位，直接降级而不发起上游调用，
        // 省去无意义的上游配额消耗（D-XX）
        if (!isPublicUnicastIpv4(ip)) {
            throw BizException(
                BizCode.SERVICE_UNAVAILABLE,
                "非公网 IP 无法地理定位（本地已短路，不调用上游）: $ip"
            )
        }
        val cacheKey = "geoip:$ip"
        return pipeline.run(
            userId = userId,
            cacheKey = cacheKey,
            category = QuotaCategory.GEO,
            perUserLimit = pipeline.quotaConfig.geoPerUserDailyLimit,
            period = pipeline.dateKey(),
            load = { pipeline.cache.getGeoIp(cacheKey) },
            fetch = {
                val result = geoIpService.locate(ip)
                val safeFormatted = ExternalContentSanitizer.sanitize(result.formatted)
                IpLocationResponse.newBuilder()
                    .setFormatted(safeFormatted)
                    .setCountry(ExternalContentSanitizer.sanitizeInline(result.country))
                    .setRegion(ExternalContentSanitizer.sanitizeInline(result.region))
                    .setCity(ExternalContentSanitizer.sanitizeInline(result.city))
                    .setLatitude(result.latitude)
                    .setLongitude(result.longitude)
                    .setTimezone(ExternalContentSanitizer.sanitizeInline(result.timezone))
                    .setIsp(ExternalContentSanitizer.sanitizeInline(result.isp))
                    .build()
            },
            store = { pipeline.cache.putGeoIp(cacheKey, it, ipGeoTtlMs()) }
        )
    }

    /** 规范化客户端 IP：x-forwarded-for 可能为 "ip, proxy1, proxy2"，取首个最原始客户端 IP；"unknown" 原样透传 */
    private fun normalizeIp(raw: String): String {
        val trimmed = raw.trim()
        val first = trimmed.split(",").firstOrNull()?.trim() ?: trimmed
        return first.ifBlank { "unknown" }
    }

    /** IP 定位 L2 TTL（毫秒），默认取配置 ipGeoTtlSeconds（24h） */
    private fun ipGeoTtlMs(): Long = pipeline.cacheConfig.l2.ipGeoTtlSeconds * 1000L

    companion object {
        /** 服务 id（契约稳定标识符，list_services 下发的 id）。 */
        const val SERVICE_ID = "geo_ip"
    }
}
