package com.nebula.common.external

/**
 * 外部服务配置聚合（D-XX）。
 *
 * 由 server 的 ConfigLoader 解析 application.conf 的 `external-service` 配置段后构建，
 * 以 Koin bean 形式注入 service 层：
 * - [WeatherService] / [com.nebula.service.external.SearchService] 取 apiKey / baseUrl / timeoutMs
 * - [com.nebula.service.external.QuotaManager] 取 [ExternalServiceQuotaConfig]
 * - [com.nebula.service.external.ExternalServiceCache] 取 [ExternalServiceCacheConfig]
 *
 * 所有密钥仅经环境变量注入，缺失时启动即报错（与 database.password 同模式）。
 */
data class ExternalServiceConfig(
    /** 和风天气配置（主源，有 Key + 配额） */
    val qweather: QWeatherConfig,
    /** wttr.in 配置（兜底层，免费无 Key） */
    val wttr: WttrConfig,
    /** Serper 搜索配置（有 Key，无免费兜底） */
    val serper: SerperConfig,
    /** 配额配置（全局共享池 + 每用户防御子限 + 阈值） */
    val quota: ExternalServiceQuotaConfig,
    /** 缓存配置（L1 本地 LRU + L2 Redis 二级） */
    val cache: ExternalServiceCacheConfig
)

/** 和风天气配置 */
data class QWeatherConfig(
    /** API Key，空时天气功能降级为 wttr.in（免费无 Key） */
    val apiKey: String,
    /** 基础地址（免费订阅用 devapi 域名） */
    val baseUrl: String,
    /** 上游 HTTP 超时（毫秒） */
    val timeoutMs: Int
)

/** wttr.in 配置（免费无 Key 兜底层） */
data class WttrConfig(
    /** 基础地址 */
    val baseUrl: String,
    /** 返回语言，默认 zh（中文天气描述） */
    val lang: String,
    /** 上游 HTTP 超时（毫秒） */
    val timeoutMs: Int
)

/** Serper 搜索配置 */
data class SerperConfig(
    /** API Key，空时搜索功能返回 SERVICE_UNAVAILABLE */
    val apiKey: String,
    /** 基础地址 */
    val baseUrl: String,
    /** 上游 HTTP 超时（毫秒） */
    val timeoutMs: Int
)

/**
 * 配额配置（D-XX）。
 *
 * 配额模型为**全局共享池**：weather-daily-limit / search-monthly-limit 是服务端全局硬上限，
 * 由全体登录用户共用一个池，并非每用户额度（详见 external-service-backend.md §3.1）。
 * 每用户防御子限（weather-per-user-daily-limit / search-per-user-monthly-limit）仅用于防止
 * 单用户把共享池瞬间烧光，须 ≤ 对应全局上限。
 */
data class ExternalServiceQuotaConfig(
    /** 配额持久化文件路径（仅 Redis 降级兜底用，正常部署不依赖） */
    val filePath: String,
    /** 和风天气全局共享日上限（全体用户共用一个池），默认 1000 */
    val weatherDailyLimit: Int,
    /** Serper 全局共享月上限，默认 2500 */
    val searchMonthlyLimit: Int,
    /** 每用户天气日上限（防御子限，须 ≤ weatherDailyLimit），默认 250 */
    val weatherPerUserDailyLimit: Int,
    /** 每用户搜索月上限（防御子限，须 ≤ searchMonthlyLimit），默认 500 */
    val searchPerUserMonthlyLimit: Int,
    /** 预警百分比（用量达到此比例时进入预警态，仅服务端日志），默认 80 */
    val warnThreshold: Int,
    /** 拒绝百分比（用量达到此比例时返回 QUOTA_EXCEEDED），默认 95 */
    val rejectThreshold: Int,
    /** 配额刷盘间隔（秒，仅文件降级态有效），默认 10 */
    val flushIntervalSeconds: Int
)

/** 缓存配置（L1 本地 LRU + L2 Redis 二级） */
data class ExternalServiceCacheConfig(
    /** L1 本地内存 LRU 配置 */
    val l1: L1Config,
    /** L2 Redis 配置 */
    val l2: L2Config
)

/** L1 本地内存 LRU 配置 */
data class L1Config(
    /** 天气 L1 TTL（秒），默认 120（2 分钟） */
    val weatherTtlSeconds: Int,
    /** 搜索 L1 TTL（秒），默认 300（5 分钟） */
    val searchTtlSeconds: Int,
    /** 最大条目数，默认 100 */
    val maxEntries: Int
)

/** L2 Redis 配置 */
data class L2Config(
    /** Redis 键前缀，避免与其它业务冲突，默认 "ext:" */
    val keyPrefix: String,
    /** 天气 L2 TTL（秒），默认 1800（30 分钟） */
    val weatherTtlSeconds: Int,
    /** GeoAPI 城市→LocationID L2 TTL（秒），默认 86400（24h） */
    val geoTtlSeconds: Int,
    /** 搜索 L2 默认 TTL（秒），默认 3600（1 小时，兜底未分类 query） */
    val searchTtlSeconds: Int,
    /** 搜索 L2 稳定类 TTL（秒），默认 21600（6 小时，search 普通网页） */
    val searchStableTtlSeconds: Int,
    /** 搜索 L2 时效类 TTL（秒），默认 600（10 分钟，news 类型） */
    val searchNewsTtlSeconds: Int
)
