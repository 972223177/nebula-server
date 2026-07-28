package com.nebula.service.external

import com.nebula.common.external.ExternalServiceConfig
import com.nebula.common.external.ExternalServiceExceptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * IP 地理定位服务（D-XX）—— 对接高德(Amap) IP 定位（国内可达，需 Key）。
 *
 * 职责：
 * - 主源 restapi.amap.com/v3/ip（需 Key、HTTPS）：输入客户端真实 IP → 解析省/市 + 经纬度（矩形中心）/时区
 * - 合并格式化为精简纯文本（与天气一致，控制 LLM token 成本）
 * - 高德仅覆盖国内 IP；海外/私有 IP 返回省/市为空 → 整体降级 SERVICE_UNAVAILABLE
 * - 高德不返回运营商与时间区，运营商字段留空、时区固定 Asia/Shanghai
 *
 * 坐标说明：高德返回 GCJ-02（国测局加密）坐标，仅用于 LLM 文本展示（如"（114.06,22.54）"），
 * 不直接用于地图绘制，故不做 WGS-84 偏移转换。
 *
 * 线程安全：JDK HttpClient 单例复用连接池；阻塞 I/O 经 `withContext(Dispatchers.IO)` 切线程。
 * 禁止自动重定向：避免含 Key 的 query 被带到重定向目标 host（凭证泄漏防御）。
 *
 * @param config 外部服务配置（取 ipGeo 的 baseUrl / apiKey / timeoutMs）
 */
class GeoIpService(
    private val config: ExternalServiceConfig
) {
    private val log = KotlinLogging.logger {}

    /** 单例 HttpClient：禁止自动重定向（最小暴露原则，与 WeatherService 同范式） */
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** 宽松解析：未知字段跳过 */
    private val json = Json { ignoreUnknownKeys = true }

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

    /**
     * 按客户端 IP 查询地理定位。
     *
     * 调用高德 IP 定位（单源，国内可达）；Key 缺失或上游返回非成功 / 海外私有 IP（省/市为空）
     * 时整体降级，抛 [com.nebula.common.exception.BizException](SERVICE_UNAVAILABLE)。
     *
     * @param ip 客户端真实 IP（由 Handler 从连接元数据提取，非客户端传入）
     * @return 定位结果（含格式化文本与结构化字段）
     */
    suspend fun locate(ip: String): GeoIpResult {
        return queryAmap(ip)
            ?: throw ExternalServiceExceptions.serviceUnavailable("IP 定位服务暂不可用")
    }

    // ─── 高德(Amap) IP 定位主源 ───

    private suspend fun queryAmap(ip: String): GeoIpResult? = withContext(Dispatchers.IO) {
        if (config.ipGeo.apiKey.isBlank()) {
            log.warn { "未配置高德 API Key，IP 定位降级" }
            return@withContext null
        }
        val url = "${config.ipGeo.baseUrl}?ip=${enc(ip)}&key=${enc(config.ipGeo.apiKey)}"
        val obj = getJson(url) ?: return@withContext null
        // 高德返回码：status="1" 且 infocode="10000" 为成功；限频/Key 无效等返回 status="0" 降级
        if (obj["status"]?.jsonPrimitive?.content != "1") {
            log.warn { "高德 IP 定位返回非成功状态: ${obj["info"]?.jsonPrimitive?.content}" }
            return@withContext null
        }
        val province = obj["province"]?.jsonPrimitive?.content ?: ""
        // 高德对直辖市/省直辖县 city 可能为 "[]" 或空，回落到 province
        val cityRaw = obj["city"]?.jsonPrimitive?.content ?: ""
        val city = if (cityRaw.isBlank() || cityRaw == "[]") province else cityRaw
        // 高德仅覆盖国内 IP；province 为空表示海外/私有 IP，整体降级
        if (province.isBlank()) return@withContext null
        val rect = obj["rectangle"]?.jsonPrimitive?.content ?: ""
        val center = parseRectangleCenter(rect) ?: return@withContext null
        buildResult(
            country = "中国",
            region = province,
            city = city,
            latitude = center.second,
            longitude = center.first,
            timezone = "Asia/Shanghai",
            isp = ""
        )
    }

    /**
     * 解析高德 rectangle 字段 "经度1,纬度1;经度2,纬度2" 为中心点 (经度,纬度)。
     * 格式异常（分段数不对 / 非数字）返回 null，由调用方降级。
     */
    private fun parseRectangleCenter(rect: String): Pair<Double, Double>? {
        val segs = rect.split(";")
        if (segs.size != 2) return null
        val first = segs[0].split(",")
        val second = segs[1].split(",")
        if (first.size != 2 || second.size != 2) return null
        val lng1 = first[0].toDoubleOrNull() ?: return null
        val lat1 = first[1].toDoubleOrNull() ?: return null
        val lng2 = second[0].toDoubleOrNull() ?: return null
        val lat2 = second[1].toDoubleOrNull() ?: return null
        return (lng1 + lng2) / 2 to (lat1 + lat2) / 2
    }

    /** 合并结构化字段为 GeoIpResult；经纬度任一缺失视为不可定位，返回 null 触发降级 */
    private fun buildResult(
        country: String,
        region: String,
        city: String,
        latitude: Double?,
        longitude: Double?,
        timezone: String,
        isp: String
    ): GeoIpResult? {
        if (latitude == null || longitude == null) return null
        val parts = listOfNotNull(
            country.takeIf { it.isNotBlank() },
            region.takeIf { it.isNotBlank() },
            city.takeIf { it.isNotBlank() }
        )
        val loc = buildString {
            if (parts.isNotEmpty()) append(parts.joinToString(" "))
            append("（%.2f,%.2f）".format(latitude, longitude))
        }
        val formatted = buildString {
            append(loc)
            if (timezone.isNotBlank()) append(" 时区$timezone")
            if (isp.isNotBlank()) append(" 运营商$isp")
        }
        return GeoIpResult(
            country = country,
            region = region,
            city = city,
            latitude = latitude,
            longitude = longitude,
            timezone = timezone,
            isp = isp,
            formatted = formatted
        )
    }

    /** 调用上游并解析为 JsonObject；非 200 / 网络异常返回 null（由调用方降级/跳过） */
    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val timeoutMs = config.ipGeo.timeoutMs
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs.toLong()))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() != 200) {
                log.warn { "IP 定位上游返回 HTTP ${resp.statusCode()} url=$url" }
                return@withContext null
            }
            json.parseToJsonElement(resp.body()).jsonObject
        } catch (e: Exception) {
            log.warn { "IP 定位上游调用失败 url=$url: ${e.message}" }
            null
        }
    }

    /** URL-encode（防止 IP 注入 query 参数，虽 IP 已被规范化仍防御） */
    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
}
