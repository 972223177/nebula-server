package com.nebula.service.external

/**
 * 网页搜索聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [SearchServiceImpl]（实现 [SearchOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler / Invoker 经 `get<SearchService>()` 获取，零改动。
 *
 * 搜索类型白名单枚举 [SearchType] 保留为本类嵌套类型，以维持 `SearchService.SearchType` 既有外部引用
 * （[com.nebula.service.external.WebSearchInvoker]）。
 */
class SearchService(impl: SearchServiceImpl) : SearchOperations by impl {
    /**
     * 搜索类型白名单枚举——仅允许此处列出的类型，拒绝未知类型，禁止透传客户端任意字符串。
     *
     * @property apiValue 映射到 Serper `type` 参数的实际值
     */
    enum class SearchType(val apiValue: String) {
        SEARCH("search"),
        NEWS("news"),
        IMAGES("images"),
        VIDEOS("videos"),
        PLACES("places"),
        MAPS("maps"),
        SHOPPING("shopping"),
        SCHOLAR("scholar"),
        PATENTS("patents"),
        AUTOCOMPLETE("autocomplete");

        companion object {
            /** 从客户端传入字符串解析白名单类型，未知返回 null（调用方应拒绝） */
            fun fromApi(s: String?): SearchType? =
                entries.firstOrNull { it.apiValue == s?.lowercase()?.trim() }
        }
    }
}
