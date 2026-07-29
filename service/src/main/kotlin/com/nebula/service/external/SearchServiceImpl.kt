package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.external.ExternalServiceConfig
import com.nebula.common.external.ExternalServiceExceptions
import com.nebula.service.external.SearchService.SearchType
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
 * 网页搜索业务契约（2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 所有公开方法声明在此接口；[SearchServiceImpl] 实现之，聚合 Facade [SearchService] 经 `by` 委托暴露。
 * Handler / Invoker 仅依赖 [SearchService]（即本接口），不感知实现拆分。
 */
interface SearchOperations {
    /** 默认网页搜索 */
    suspend fun search(query: String, maxResults: Int = 5): SearchResponse

    /** 按指定类型搜索（预留扩展：news/im/等） */
    suspend fun searchByType(query: String, type: SearchType, maxResults: Int = 5): SearchResponse
}

/** 单条搜索结果 */
data class SearchItem(val title: String, val snippet: String, val url: String)

/** 完整搜索结果 */
data class SearchResult(val formatted: String, val items: List<SearchItem>)

/**
 * 网页搜索服务实现（D-XX）—— 对接 Serper API 全部搜索类型。
 *
 * 当前重点关注类型：
 * - search（默认网页搜索）：含 knowledgeGraph + organic + peopleAlsoAsk + relatedSearches + answerBox
 * - news（新闻搜索）：含标题/来源/发布时间/摘要
 *
 * 行为：
 * - 统一 POST 到 https://google.serper.dev/search
 * - 通过 type 参数区分搜索类型；**type 必须白名单校验**（仅允许 [SearchType] 枚举值）
 * - **max_results 服务端裁剪到 [1,10]**，防止客户端传超大值
 * - **输入安全**：query 作为上游 body 参数正确 JSON 转义，禁止原样拼接
 * - API Key 未配置时抛 SERVICE_UNAVAILABLE；API 超时抛 SERVICE_UNAVAILABLE；上游 429 映射 RATE_LIMITED
 * - **日志安全**：禁止记录含 X-API-KEY 的完整上游请求/响应，异常日志对 Key 脱敏
 *
 * 线程安全：JDK HttpClient 单例复用连接池；阻塞 I/O 经 `withContext(Dispatchers.IO)` 切线程。
 *
 * @param config 外部服务配置（取 serper 的 apiKey / baseUrl / timeoutMs）
 */
class SearchServiceImpl(
    private val config: ExternalServiceConfig
) : SearchOperations {
    private val log = KotlinLogging.logger {}
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER) // 禁止自动重定向：避免 X-API-KEY 请求头被带到重定向目标 host
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** 默认网页搜索 */
    override suspend fun search(query: String, maxResults: Int): SearchResponse =
        searchByType(query, SearchType.SEARCH, maxResults)

    /** 按指定类型搜索（预留扩展：news/im/等） */
    override suspend fun searchByType(query: String, type: SearchType, maxResults: Int): SearchResponse {
        val q = query.trim()
        if (q.isEmpty()) return SearchResponse.newBuilder().setFormatted("（搜索词为空）").build()
        if (config.serper.apiKey.isBlank()) {
            log.warn { "Serper 搜索被拒绝：未配置 API Key（type=${type.apiValue}）" }
            throw ExternalServiceExceptions.serviceUnavailable("未配置 SERPER_API_KEY，搜索功能不可用")
        }
        val n = maxResults.coerceIn(1, 10)
        log.debug { "Serper 搜索请求 type=${type.apiValue} queryLen=${q.length} num=$n" }
        val result = try {
            callSerper(q, type.apiValue, n)
        } catch (e: BizException) {
            if (e.bizCode == BizCode.RATE_LIMITED) {
                log.warn { "Serper 上游限流（429），type=${type.apiValue} queryLen=${q.length}" }
            } else {
                log.error(e) { "Serper 搜索业务异常 type=${type.apiValue} queryLen=${q.length} code=${e.bizCode.code}" }
            }
            throw e
        } catch (e: Exception) {
            log.error(e) { "Serper 搜索未预期异常 type=${type.apiValue} queryLen=${q.length}" }
            throw e
        }
        if (result.items.isEmpty()) {
            log.info { "Serper 搜索无结果 type=${type.apiValue} queryLen=${q.length}" }
        } else {
            log.debug { "Serper 搜索成功 type=${type.apiValue} returned=${result.items.size}" }
        }
        return result.toSearchResponse()
    }

    /** 调用 Serper 并解析结果；429 映射 RATE_LIMITED，其余失败映射 SERVICE_UNAVAILABLE */
    private suspend fun callSerper(query: String, type: String, num: Int): SearchResult = withContext(Dispatchers.IO) {
        val body = """{"q":${jsonStr(query)},"type":${jsonStr(type)},"num":$num,"hl":"zh-cn","gl":"cn"}"""
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${config.serper.baseUrl}/search"))
            .timeout(Duration.ofMillis(config.serper.timeoutMs.toLong()))
            .header("X-API-KEY", config.serper.apiKey) // 注意：日志中严禁输出此 Key
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        when (resp.statusCode()) {
            429 -> throw BizException(BizCode.RATE_LIMITED, "Serper 上游限流（429）")
            !in 200..299 -> throw ExternalServiceExceptions.serviceUnavailable("Serper 返回 HTTP ${resp.statusCode()}")
        }
        parseSerper(resp.body())
    }

    /** 解析 Serper JSON，提取 organic 结果并合并为格式化文本 */
    private fun parseSerper(body: String): SearchResult {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw ExternalServiceExceptions.serviceUnavailable("Serper 响应解析失败: ${e.message}")
        }
        val organic = obj["organic"]?.jsonArray ?: JsonArray(emptyList())
        val items = organic.mapNotNull { el ->
            val o = (el as? JsonObject) ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = o["link"]?.jsonPrimitive?.content ?: ""
            val snippet = stripHtml(o["snippet"]?.jsonPrimitive?.content ?: "")
            SearchItem(title, snippet, url)
        }
        val formatted = buildString {
            appendLine("搜索结果（共 ${items.size} 条）：")
            items.forEachIndexed { i, it ->
                appendLine("${i + 1}. [${it.title}] ${it.snippet}")
            }
        }.trimEnd()
        return SearchResult(formatted, items)
    }

    /** 去除 HTML 标签，避免上游网页内容注入格式 */
    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()

    /** JSON 字符串转义（防止 query 注入 body） */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
