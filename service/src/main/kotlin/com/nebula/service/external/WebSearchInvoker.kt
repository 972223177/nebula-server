package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem
import com.nebula.common.external.MissingParamException
import com.nebula.common.external.ParamIssue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 网页搜索服务执行体（D-XX）—— 封装 web_search 的参数解析 / 白名单校验 / 配额 / 缓存 / 上游调用 / 净化。
 *
 * 复用 [ExternalServicePipeline] 统一管线；搜索结果含结构化字段（title/snippet/url），
 * 逐字段净化后由 [store] 经专用缓存方法写回（避免通用 put 仅存 formatted 丢结构化数据）。
 *
 * @param pipeline 通用编排管线
 * @param searchService Serper 搜索服务
 */
class WebSearchInvoker(
    private val pipeline: ExternalServicePipeline,
    private val searchService: SearchService
) : ExternalServiceInvoker<SearchResponse> {

    override val serviceId: String = SERVICE_ID

    override suspend fun invoke(userId: Long, clientIp: String, paramsJson: String): SearchResponse {
        val obj = parseParams(paramsJson)
        val query = (obj["query"] as? JsonPrimitive)?.content?.trim()
        if (query.isNullOrBlank()) throw MissingParamException(listOf(
            ParamIssue(param = "query", reason = if (query == null) "missing" else "empty", hint = "请填写搜索关键词，如 最新 AI 新闻")
        ))
        // 搜索类型白名单校验（未知类型拒绝，禁止透传客户端任意字符串）
        val typeRaw = (obj["search_type"] as? JsonPrimitive)?.content ?: "search"
        val type = SearchService.SearchType.fromApi(typeRaw)
            ?: throw MissingParamException(listOf(
                ParamIssue(param = "search_type", reason = "invalid_value", hint = "仅支持 search / news / images 之一")
            ))
        val maxResults = (obj["max_results"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 5
        val normalized = query.trim().lowercase().replace(Regex("\\s+"), " ")
        val cacheKey = "search:$normalized"
        return pipeline.run(
            userId = userId,
            cacheKey = cacheKey,
            category = QuotaCategory.SEARCH,
            perUserLimit = pipeline.quotaConfig.searchPerUserMonthlyLimit,
            period = pipeline.monthKey(),
            load = { pipeline.cache.getSearchResponse(cacheKey) },
            fetch = {
                val resp = searchService.searchByType(query, type, maxResults)
                val safeFormatted = ExternalContentSanitizer.sanitize(resp.formatted)
                // 结构化结果同样来自不可信第三方，逐字段净化（去控制字符+注入词，不包裹标记以免破坏客户端逐条渲染）
                val safeItems = resp.itemsList.map { item ->
                    SearchResultItem.newBuilder()
                        .setTitle(ExternalContentSanitizer.sanitizeInline(item.title))
                        .setSnippet(ExternalContentSanitizer.sanitizeInline(item.snippet))
                        .setUrl(ExternalContentSanitizer.sanitizeInline(item.url))
                        .build()
                }
                SearchResponse.newBuilder()
                    .setFormatted(safeFormatted)
                    .addAllItems(safeItems)
                    .build()
            },
            store = { pipeline.cache.putSearchResponse(cacheKey, it, searchTtlMs(type)) }
        )
    }

    /** 按搜索类型选择 L2 TTL（稳定类长 TTL 省调用；时效类短 TTL 防过期） */
    private fun searchTtlMs(type: SearchService.SearchType): Long = when (type) {
        SearchService.SearchType.NEWS -> pipeline.cacheConfig.l2.searchNewsTtlSeconds * 1000L
        SearchService.SearchType.SEARCH -> pipeline.cacheConfig.l2.searchStableTtlSeconds * 1000L
        else -> pipeline.cacheConfig.l2.searchTtlSeconds * 1000L
    }

    companion object {
        /** 服务 id（契约稳定标识符，list_services 下发的 id）。 */
        const val SERVICE_ID = "web_search"
    }
}
