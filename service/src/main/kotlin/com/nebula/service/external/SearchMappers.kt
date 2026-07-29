package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem

/**
 * 搜索结果 → [SearchResponse] Protobuf 映射（2026-07-29 从 [SearchServiceImpl] 内联构造迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * 统一收口 [SearchResult]（format 文本 + 条目列表）到 [SearchResponse] 的拼装，
 * 杜绝 [SearchServiceImpl] 内联 `newBuilder()` 的双源真相。
 */
fun SearchResult.toSearchResponse(): SearchResponse {
    val builder = SearchResponse.newBuilder().setFormatted(formatted)
    items.forEach { item ->
        builder.addItems(
            SearchResultItem.newBuilder()
                .setTitle(item.title)
                .setSnippet(item.snippet)
                .setUrl(item.url)
        )
    }
    return builder.build()
}
