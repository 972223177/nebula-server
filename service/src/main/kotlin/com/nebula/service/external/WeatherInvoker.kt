package com.nebula.service.external

import com.nebula.chat.external.WeatherResponse
import com.nebula.common.external.MissingParamException
import com.nebula.common.external.ParamIssue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 天气服务执行体（D-XX）—— 封装 weather 的参数解析 / 配额 / 缓存 / 上游调用 / 净化。
 *
 * 复用 [ExternalServicePipeline] 统一管线，仅以 lambda 表达与天气相关的差异；新增其他服务
 * 只需新增一个 [ExternalServiceInvoker] 实现，编排类不再增长。
 *
 * @param pipeline 通用编排管线（配额/缓存/净化骨架）
 * @param weatherService 和风天气服务（含 wttr.in 降级）
 */
class WeatherInvoker(
    private val pipeline: ExternalServicePipeline,
    private val weatherService: WeatherService
) : ExternalServiceInvoker<WeatherResponse> {

    override val serviceId: String = SERVICE_ID

    override suspend fun invoke(userId: Long, clientIp: String, paramsJson: String): WeatherResponse {
        // city 为可选参数：缺省时不自动 geo 定位（不按 clientIp 触达上游），以结构化 param_issues 告知前端补参
        val city = (parseParams(paramsJson)["city"] as? JsonPrimitive)?.content?.trim() ?: ""
        if (city.isEmpty()) {
            throw MissingParamException(listOf(
                ParamIssue(param = "city", reason = "missing", hint = "请指定城市名，如 北京")
            ))
        }
        val cacheKey = "weather:${city.lowercase()}"
        return pipeline.run(
            userId = userId,
            cacheKey = cacheKey,
            category = QuotaCategory.WEATHER,
            perUserLimit = pipeline.quotaConfig.weatherPerUserDailyLimit,
            period = pipeline.dateKey(),
            estimate = 8,
            load = { pipeline.cache.get(cacheKey)?.let { WeatherResponse.newBuilder().setFormatted(it).build() } },
            fetch = {
                val resp = weatherService.queryWeather(city)
                val safe = ExternalContentSanitizer.sanitize(resp.formatted)
                WeatherResponse.newBuilder().setFormatted(safe).build()
            },
            store = { pipeline.cache.put(cacheKey, it.formatted, QuotaCategory.WEATHER) }
        )
    }

    companion object {
        /** 服务 id（契约稳定标识符，list_services 下发的 id）。 */
        const val SERVICE_ID = "weather"
    }
}
