package com.nebula.service.external

import com.nebula.chat.external.WeatherResponse

/**
 * 天气格式化文本 → [WeatherResponse] Protobuf 映射（2026-07-29 从 [WeatherServiceImpl] 内联构造迁入，仿 `conversation/ConversationMappers.kt`）。
 */
fun buildWeatherResponse(formatted: String): WeatherResponse =
    WeatherResponse.newBuilder().setFormatted(formatted).build()
