package com.nebula.service.external

import com.nebula.chat.external.WeatherResponse
import com.nebula.common.external.ExternalServiceConfig
import com.nebula.common.external.ExternalServiceExceptions
import com.nebula.common.external.MissingParamException
import com.nebula.common.external.ParamIssue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** 和风天气鉴权/限流类错误码：命中时整体降级 wttr.in（而非返回残缺数据） */
private val AUTH_ERROR_CODES = setOf("401", "403", "429")

/** 和风天气鉴权/限流失败专用异常：触发 queryWeather 整体降级 wttr.in（非致命，不对外暴露 Key） */
private class QWeatherAuthException(message: String) : Exception(message)

/** 合并后的天气数据（各字段为 null 时格式化时自动跳过） */
data class WeatherData(
    val current: String?,
    val forecast: String?,
    val hourly: String?,
    val minutely: String?,
    val airQuality: String?,
    val warning: String?,
    val indices: String?,
    val astronomy: String?
)

/**
 * 天气查询业务契约（2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 所有公开方法声明在此接口；[WeatherServiceImpl] 实现之，聚合 Facade [WeatherService] 经 `by` 委托暴露。
 */
interface WeatherOperations {
    /**
     * 查询天气：优先和风天气（有 Key），超时/报错/无 Key 整体降级 wttr.in。
     *
     * @param city 城市名；**可选**，为空时不自动 geo 定位（不按 clientIp 触达上游），直接返回"未指定城市"提示文本（零上游调用、不消耗配额）。
     */
    suspend fun queryWeather(city: String): WeatherResponse
}

/**
 * 天气查询服务实现（D-XX）—— 对接和风天气全部 API。
 *
 * 职责：
 * - GeoAPI 城市搜索：城市名 → 经纬度 + LocationID；**结果本地缓存（TTL 24h）**，避免每次请求多发一次上游调用
 * - 实况天气 + 逐天预报（7d）+ 逐小时预报（24h）+ 分钟降水 + 空气质量 + 天气预警 + 生活指数 + 天文
 * - 合并格式化为**精简**纯文本（当前实况 + 3 天预报为主），控制 LLM token 成本
 * - 城市不存在（GeoAPI 未匹配到 LocationID）整体降级 wttr.in（而非向客户端暴露硬错误）
 * - 和风超时/报错时整体降级为 wttr.in（免费，无需 Key，支持 lang 中文描述，返回简易格式）
 *
 * 缓存策略：本服务**只缓存 GeoAPI 的 LocationID**（§4.2），天气结果缓存由 Orchestrator 统一负责，
 * 本服务保持"纯上游调用 + 格式化"职责，便于配额联动（缓存命中不扣配额）。
 *
 * 线程安全：JDK HttpClient 单例复用连接池；阻塞 I/O 经 `withContext(Dispatchers.IO)` 切线程。
 *
 * @param config 外部服务配置（取 qweather/wttr 的 apiKey / baseUrl / timeoutMs）
 * @param cache 外部服务缓存（仅用于 GeoAPI LocationID 24h 缓存）
 */
class WeatherServiceImpl(
    private val config: ExternalServiceConfig,
    private val cache: ExternalServiceCache
) : WeatherOperations {
    private val log = KotlinLogging.logger {}

    /** 和风主源 HttpClient：禁止自动重定向，避免含 API Key 的 query 被带到重定向目标 host（凭证泄漏防御） */
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** wttr.in 降级源 HttpClient：免费无 Key，无凭证泄漏风险，允许跟随重定向（其 CDN 偶尔 302） */
    private val wttrHttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** 宽松解析：未知字段跳过 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 查询天气：优先和风天气（有 Key），超时/报错/无 Key 整体降级 wttr.in。
     *
     * @param city 城市名（调用方已 trim；内部再规范化）
     * @return 格式化天气文本封装的 WeatherResponse
     */
    override suspend fun queryWeather(city: String): WeatherResponse {
        val c = city.trim()
        // city 缺省时不自动 geo 定位（避免按 clientIp 触达上游、消耗配额），以结构化 param_issues 告知前端补参
        if (c.isEmpty()) throw MissingParamException(listOf(
            ParamIssue(param = "city", reason = "missing", hint = "请指定城市名，如 北京")
        ))

        val formatted = if (config.qweather.apiKey.isNotBlank()) {
            try {
                // queryQWeather 返回 null 表示和风无可用数据（鉴权失败/全路报错），整体降级 wttr.in
                queryQWeather(c) ?: run {
                    log.warn { "和风天气无可用数据，降级 wttr.in: $c" }
                    queryWttr(c)
                }
            } catch (e: com.nebula.common.exception.BizException) {
                // 城市不存在（GeoAPI 未匹配到 LocationID）属上游数据缺失，降级 wttr.in 而非向客户端暴露硬错误
                if (e.bizCode == com.nebula.common.BizCode.INVALID_CITY) {
                    log.warn { "和风城市解析失败(${e.message})，降级 wttr.in: $c" }
                    queryWttr(c)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                log.warn { "和风天气查询失败，降级 wttr.in: ${e.message}" }
                queryWttr(c)
            }
        } else {
            log.info { "未配置 QWEATHER_API_KEY，天气降级 wttr.in: $c" }
            queryWttr(c)
        }
        return buildWeatherResponse(formatted)
    }

    // ─── 和风天气主源 ───

    private suspend fun queryQWeather(city: String): String? = coroutineScope {
        // GeoAPI 取 LocationID（带 24h 缓存）
        val (locationId, latlon) = lookupLocation(city)
        // 并发拉取 7 路数据（单路超时收紧到 ≤3s，总预算控制在 Dispatcher 10s 内）
        val nowDef = async { getQWJson("/v7/weather/now", locationId) }
        val dailyDef = async { getQWJson("/v7/weather/7d", locationId) }
        val hourlyDef = async { getQWJson("/v7/weather/24h", locationId) }
        val airDef = async { getQWJson("/v7/air/now", locationId) }
        val warningDef = async { getQWJson("/v7/warning/now", locationId) }
        val indicesDef = async { getQWJson("/v7/indices/1d?type=1,2,3,5,8,9", locationId) }
        val astronomyDef = async { getQWJson("/v7/astronomy/sunrise-sunset", locationId) }
        val minutelyDef = if (latlon.isNotEmpty()) async { getQWJson("/v7/minutely/5m", latlon) } else null

        val data = WeatherData(
            current = formatNow(nowDef.await()),
            forecast = formatDaily(dailyDef.await()),
            hourly = formatHourly(hourlyDef.await()),
            minutely = formatMinutely(minutelyDef?.await()),
            airQuality = formatAir(airDef.await()),
            warning = formatWarning(warningDef.await()),
            indices = formatIndices(indicesDef.await()),
            astronomy = formatAstronomy(astronomyDef.await())
        )
        val formatted = WeatherResponseFormatter.format(data)
        // 全部子模块均缺失（如各路上游报错）时返回 null，由 queryWeather 整体降级 wttr.in
        if (formatted == WeatherResponseFormatter.EMPTY_WEATHER_TEXT) null else formatted
    }

    /** GeoAPI 城市搜索，结果缓存 24h（key geo:<city> = "id;lat,lon"） */
    private suspend fun lookupLocation(city: String): Pair<String, String> {
        val ck = "geo:${city.lowercase()}"
        val cached = cache.get(ck)
        if (cached != null) {
            val parts = cached.split(";", limit = 2)
            if (parts.size == 2) return parts[0] to parts[1]
        }
        val obj = getQWJson("/v2/city/lookup", city)
            ?: throw ExternalServiceExceptions.invalidCity(city).also {
                log.warn { "和风 GeoAPI 未返回城市数据（city=$city），将降级 wttr.in" }
            }
        val arr = obj["location"]?.jsonArray
        if (arr.isNullOrEmpty()) throw ExternalServiceExceptions.invalidCity(city).also {
            log.warn { "和风 GeoAPI 返回空 location 数组（city=$city），将降级 wttr.in" }
        }
        val loc = arr[0].jsonObject
        val id = loc["id"]?.jsonPrimitive?.content
            ?: throw ExternalServiceExceptions.invalidCity(city)
        val lat = loc["lat"]?.jsonPrimitive?.content ?: ""
        val lon = loc["lon"]?.jsonPrimitive?.content ?: ""
        cache.put(ck, "$id;$lat,$lon", QuotaCategory.WEATHER, config.cache.l2.geoTtlSeconds * 1000L)
        return id to "$lat,$lon"
    }

    /** 调用和风 API 并解析为 JsonObject；非 200 / 网络异常返回 null（由调用方降级/跳过） */
    private suspend fun getQWJson(path: String, location: String): JsonObject? = withContext(Dispatchers.IO) {
        val timeoutMs = minOf(config.qweather.timeoutMs, 3000)
        val url = "${config.qweather.baseUrl}$path" +
            "?location=${enc(location)}&key=${enc(config.qweather.apiKey)}"
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs.toLong()))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() != 200) {
                log.warn { "和风 API $path 返回 HTTP ${resp.statusCode()}" }
                return@withContext null
            }
            val obj = json.parseToJsonElement(resp.body()).jsonObject
            val code = obj["code"]?.jsonPrimitive?.content
            if (code != "200") {
                // 鉴权/限流类错误抛出专用异常，由 queryWeather 捕获后整体降级 wttr.in（而非返回残缺数据）
                if (code in AUTH_ERROR_CODES) {
                    throw QWeatherAuthException("和风天气鉴权/限流失败(code=$code)")
                }
                log.warn { "和风 API $path 业务码异常 code=$code（location=$location），视为无数据" }
                return@withContext null
            }
            obj
        } catch (e: Exception) {
            log.warn { "和风 API $path 调用失败: ${e.message}" }
            null
        }
    }

    // ─── 各模块文本格式化（缺失模块自动跳过） ───

    private fun formatNow(obj: JsonObject?): String? {
        val now = obj?.get("now")?.jsonObject ?: return null
        val temp = now["temp"]?.jsonPrimitive?.content ?: "?"
        val feels = now["feelsLike"]?.jsonPrimitive?.content ?: "?"
        val text = now["text"]?.jsonPrimitive?.content ?: ""
        val hum = now["humidity"]?.jsonPrimitive?.content
        return buildString {
            append("当前$text，${temp}°C（体感${feels}°C）")
            if (hum != null) append(" 湿度${hum}%")
        }
    }

    private fun formatDaily(obj: JsonObject?): String? {
        val arr = obj?.get("daily")?.jsonArray ?: return null
        if (arr.isEmpty()) return null
        val sb = StringBuilder("未来三天：")
        arr.take(3).forEach { d ->
            val o = d.jsonObject
            sb.append("${o["fxDate"]?.jsonPrimitive?.content}：${o["textDay"]?.jsonPrimitive?.content} ${o["tempMin"]?.jsonPrimitive?.content}~${o["tempMax"]?.jsonPrimitive?.content}°C；")
        }
        return sb.toString()
    }

    private fun formatHourly(obj: JsonObject?): String? {
        val arr = obj?.get("hourly")?.jsonArray ?: return null
        if (arr.isEmpty()) return null
        val sb = StringBuilder("未来几小时：")
        arr.take(6).forEach { h ->
            val o = h.jsonObject
            val t = o["fxTime"]?.jsonPrimitive?.content ?: ""
            val hour = if (t.length >= 13) t.substring(11, 13) else t
            sb.append("$hour 时 ${o["text"]?.jsonPrimitive?.content} ${o["temp"]?.jsonPrimitive?.content}°C；")
        }
        return sb.toString()
    }

    private fun formatMinutely(obj: JsonObject?): String? {
        val summary = obj?.get("summary")?.jsonPrimitive?.content ?: return null
        return "分钟降水：$summary"
    }

    private fun formatAir(obj: JsonObject?): String? {
        val now = obj?.get("now")?.jsonObject ?: return null
        return "空气质量：AQI ${now["aqi"]?.jsonPrimitive?.content}（等级${now["level"]?.jsonPrimitive?.content}）" +
            "PM2.5 ${now["pm2p5"]?.jsonPrimitive?.content}μg/m³"
    }

    private fun formatWarning(obj: JsonObject?): String? {
        val arr = obj?.get("warning")?.jsonArray ?: return null
        if (arr.isEmpty()) return "天气预警：无"
        val sb = StringBuilder("天气预警：")
        arr.forEach { w ->
            val o = w.jsonObject
            sb.append("${o["title"]?.jsonPrimitive?.content ?: "预警"}（${o["severity"]?.jsonPrimitive?.content ?: ""}）")
        }
        return sb.toString()
    }

    private fun formatIndices(obj: JsonObject?): String? {
        val arr = obj?.get("daily")?.jsonArray ?: return null
        if (arr.isEmpty()) return null
        val sb = StringBuilder("生活指数：")
        arr.take(6).forEach { i ->
            val o = i.jsonObject
            sb.append("${o["name"]?.jsonPrimitive?.content}：${o["category"]?.jsonPrimitive?.content}；")
        }
        return sb.toString()
    }

    private fun formatAstronomy(obj: JsonObject?): String? {
        obj ?: return null
        val sunRise = obj["sunrise"]?.jsonPrimitive?.content
        val sunSet = obj["sunset"]?.jsonPrimitive?.content
        val moon = obj["moonPhase"]?.jsonPrimitive?.content
        return "日出：$sunRise 日落：$sunSet，月相：$moon"
    }

    // ─── wttr.in 降级（免费无 Key，简易格式） ───

    private suspend fun queryWttr(city: String): String = withContext(Dispatchers.IO) {
        val fmt = "%l%0A%c %t (体感 %f)%0A湿度 %h 风速 %w"
        val url = "${config.wttr.baseUrl}/${enc(city)}?format=${enc(fmt)}&lang=${enc(config.wttr.lang)}"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(config.wttr.timeoutMs.toLong()))
            .GET()
            .build()
        val resp = wttrHttpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (resp.statusCode() != 200) {
            throw ExternalServiceExceptions.serviceUnavailable("wttr.in 降级失败: HTTP ${resp.statusCode()}")
        }
        resp.body()
    }

    /** URL-encode（防止城市名注入 query 参数） */
    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}

/**
 * 天气数据格式化器（D-XX）：将所有数据合并为一段完整的 LLM 注入文本，缺失的模块自动跳过。
 */
object WeatherResponseFormatter {
    /** 全部子模块缺失时的空标记（调用方据此判定和风无可用数据并降级 wttr.in） */
    const val EMPTY_WEATHER_TEXT = "（天气数据暂不可用）"

    fun format(data: WeatherData): String {
        val parts = listOfNotNull(
            data.current,
            data.forecast,
            data.hourly,
            data.minutely,
            data.airQuality,
            data.warning,
            data.indices,
            data.astronomy
        )
        return if (parts.isEmpty()) EMPTY_WEATHER_TEXT else parts.joinToString("\n")
    }
}
