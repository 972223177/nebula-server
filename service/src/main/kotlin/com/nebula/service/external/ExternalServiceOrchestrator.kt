package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem
import com.nebula.chat.external.WeatherResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceExceptions
import com.nebula.common.external.ExternalServiceQuotaConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

/**
 * 外部服务业务编排（D-XX）。
 *
 * 完整流程：
 * - 天气：缓存命中（不扣配额）→ 全局配额拒绝线检查 → 每用户防御子限 → 预估扣减（约 8 次上游调用）→ 调 WeatherService → 写缓存
 * - 搜索：缓存命中（不扣配额）→ 全局配额拒绝线检查 → 每用户防御子限 → 扣减 1 → 调 SearchService → 写缓存（按 SearchType 分级 TTL）
 *
 * 职责：
 * - 缓存查询（命中直接返回，不扣配额）——缓存由本编排层统一负责（§4.2/§4.6）
 * - 配额检查：全局共享池（1000/天、2500/月）硬上限 + 每用户防御子限（250/天、500/月，≤ 全局上限）
 * - 调用上游 Service → 合并格式化 → 生成 Proto 响应
 * - 统一异常映射：全部抛 [BizException](BizCode) 由 ExceptionInterceptor 写入 Response.code/msg（§2/§4.3）
 *
 * 鉴权与每用户限额：userId 由 Handler 层经 `coroutineContext.requireSession()` 取得后**作为参数传入**，
 * 编排层不直接依赖 gateway 的 SessionKey（遵守 common←repository←service←gateway 单向依赖）。
 *
 * @param weatherService 和风天气服务
 * @param searchService Serper 搜索服务
 * @param quotaManager 全局配额管理器（Redis 主存储 + 文件降级）
 * @param cache 外部服务缓存（L1 + L2）
 * @param perUserQuotaStore 每用户防御子限存储（Redis 计数）
 * @param quotaConfig 配额配置（限额、阈值、每用户子限）
 * @param cacheConfig 缓存配置（L2 分级 TTL）
 */
class ExternalServiceOrchestrator(
    private val weatherService: WeatherService,
    private val searchService: SearchService,
    private val quotaManager: QuotaManager,
    private val cache: ExternalServiceCache,
    private val perUserQuotaStore: PerUserQuotaStore,
    private val quotaConfig: ExternalServiceQuotaConfig,
    private val cacheConfig: ExternalServiceCacheConfig
) {
    private val log = KotlinLogging.logger {}

    /**
     * 天气查询编排。
     *
     * @param userId 调用方用户 ID（用于每用户防御子限）
     * @param city 城市名
     * @return 格式化天气文本封装的 WeatherResponse
     * @throws BizException QUOTA_EXCEEDED / INVALID_CITY / SERVICE_UNAVAILABLE（由 ExceptionInterceptor 转为 Response）
     */
    suspend fun queryWeather(userId: Long, city: String): WeatherResponse {
        val cacheKey = "weather:${city.trim().lowercase()}"
        cache.get(cacheKey)?.let {
            log.debug { "天气缓存命中 userId=$userId city=$city" }
            return WeatherResponse.newBuilder().setFormatted(it).build()
        }
        if (quotaManager.usagePercent(QuotaCategory.WEATHER) >= quotaConfig.rejectThreshold) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.WEATHER))
        }
        if (!perUserQuotaStore.tryConsume(userId, QuotaCategory.WEATHER, quotaConfig.weatherPerUserDailyLimit, dateKey())) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.WEATHER))
        }
        if (!quotaManager.consume(QuotaCategory.WEATHER, 8)) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.WEATHER))
        }
        val resp = weatherService.queryWeather(city)
        val safe = ExternalContentSanitizer.sanitize(resp.formatted) // §7.1 间接提示注入防护
        cache.put(cacheKey, safe, QuotaCategory.WEATHER)
        return WeatherResponse.newBuilder().setFormatted(safe).build()
    }

    /**
     * 网页搜索编排。
     *
     * @param userId 调用方用户 ID
     * @param query 搜索词
     * @param maxResults 最大结果数（将被裁剪到 [1,10]）
     * @param searchType 搜索类型字符串（白名单校验，见 [SearchService.SearchType]）
     * @return 格式化搜索结果封装的 SearchResponse
     * @throws BizException QUOTA_EXCEEDED / INVALID_PARAM（未知类型）/ SERVICE_UNAVAILABLE / RATE_LIMITED
     */
    suspend fun webSearch(userId: Long, query: String, maxResults: Int, searchType: String = "search"): SearchResponse {
        val type = SearchService.SearchType.fromApi(searchType)
            ?: throw BizException(BizCode.INVALID_PARAM, "不支持的搜索类型: $searchType")
        val normalized = query.trim().lowercase().replace(Regex("\\s+"), " ")
        val cacheKey = "search:$normalized"
        // 命中返回完整响应（含 items）；通用 get 仅存 formatted，故搜索专用方法避免丢结构化字段
        cache.getSearchResponse(cacheKey)?.let {
            log.debug { "搜索缓存命中 userId=$userId query=$query" }
            return it
        }
        if (quotaManager.usagePercent(QuotaCategory.SEARCH) >= quotaConfig.rejectThreshold) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.SEARCH))
        }
        if (!perUserQuotaStore.tryConsume(userId, QuotaCategory.SEARCH, quotaConfig.searchPerUserMonthlyLimit, monthKey())) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.SEARCH))
        }
        if (!quotaManager.consume(QuotaCategory.SEARCH, 1)) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(QuotaCategory.SEARCH))
        }
        val resp = searchService.searchByType(query, type, maxResults)
        val safeFormatted = ExternalContentSanitizer.sanitize(resp.formatted) // §7.1 间接提示注入防护（主文本包裹边界标记）
        // 结构化结果同样来自不可信第三方，逐字段净化（去控制字符+注入词，不包裹标记以免破坏客户端逐条渲染）
        val safeItems = resp.itemsList.map { item ->
            SearchResultItem.newBuilder()
                .setTitle(ExternalContentSanitizer.sanitizeInline(item.title))
                .setSnippet(ExternalContentSanitizer.sanitizeInline(item.snippet))
                .setUrl(ExternalContentSanitizer.sanitizeInline(item.url))
                .build()
        }
        val response = SearchResponse.newBuilder()
            .setFormatted(safeFormatted)
            .addAllItems(safeItems)
            .build()
        cache.putSearchResponse(cacheKey, response, searchTtlMs(type))
        return response
    }

    /** 按搜索类型选择 L2 TTL（稳定类长 TTL 省调用；时效类短 TTL 防过期） */
    private fun searchTtlMs(type: SearchService.SearchType): Long = when (type) {
        SearchService.SearchType.NEWS -> cacheConfig.l2.searchNewsTtlSeconds * 1000L
        SearchService.SearchType.SEARCH -> cacheConfig.l2.searchStableTtlSeconds * 1000L
        else -> cacheConfig.l2.searchTtlSeconds * 1000L
    }

    /** 当日日期键（每用户天气日限周期） */
    private fun dateKey(): String = LocalDate.now().toString()

    /** 当月键（每用户搜索月限周期） */
    private fun monthKey(): String {
        val d = LocalDate.now()
        return "%04d-%02d".format(d.year, d.monthValue)
    }
}
