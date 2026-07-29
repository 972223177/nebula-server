package com.nebula.service.external

/**
 * 天气查询聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [WeatherServiceImpl]（实现 [WeatherOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler 经 `get<WeatherService>()` 获取，零改动。
 */
class WeatherService(impl: WeatherServiceImpl) : WeatherOperations by impl
