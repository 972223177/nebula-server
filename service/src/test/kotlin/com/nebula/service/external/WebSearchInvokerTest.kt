package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceQuotaConfig
import com.nebula.common.external.L1Config
import com.nebula.common.external.L2Config
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * WebSearchInvoker 单元测试 —— 覆盖网页搜索执行体的参数校验（缺 query/空白/未知类型/非法 JSON）/
 * 缓存命中 / 上游调用 + 写缓存。
 *
 * 与 WeatherInvokerTest 同构：参数解析/白名单校验逻辑已下沉到各 Invoker，执行体测试聚焦此处。
 * 每个测试在 [BeforeEach] 创建全新 mock，避免 coEvery 桩在用例间相互污染。
 */
class WebSearchInvokerTest {

    private lateinit var searchService: SearchService
    private lateinit var cache: ExternalServiceCache
    private lateinit var quotaManager: QuotaManager
    private lateinit var perUserQuotaStore: PerUserQuotaStore
    private lateinit var pipeline: ExternalServicePipeline
    private lateinit var invoker: WebSearchInvoker

    private val quotaConfig = ExternalServiceQuotaConfig(
        filePath = "/tmp/nebula-test-quota.properties",
        weatherDailyLimit = 1000,
        searchMonthlyLimit = 2500,
        weatherPerUserDailyLimit = 250,
        searchPerUserMonthlyLimit = 500,
        geoDailyLimit = 500,
        geoPerUserDailyLimit = 50,
        geoMonthlyLimit = 15000,
        warnThreshold = 80,
        rejectThreshold = 95,
        flushIntervalSeconds = 10
    )
    private val cacheConfig = ExternalServiceCacheConfig(
        l1 = L1Config(weatherTtlSeconds = 120, searchTtlSeconds = 300, maxEntries = 100),
        l2 = L2Config(
            keyPrefix = "ext:",
            weatherTtlSeconds = 1800,
            geoTtlSeconds = 86400,
            searchTtlSeconds = 3600,
            searchStableTtlSeconds = 21600,
            searchNewsTtlSeconds = 600,
            ipGeoTtlSeconds = 86400
        )
    )

    @BeforeEach
    fun setup() {
        searchService = mockk()
        cache = mockk()
        quotaManager = mockk()
        perUserQuotaStore = mockk()
        pipeline = ExternalServicePipeline(quotaManager, cache, perUserQuotaStore, quotaConfig, cacheConfig)
        invoker = WebSearchInvoker(pipeline, searchService)
    }

    @Test
    fun missingQueryThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", "{}") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun blankQueryThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", """{"query":""}""") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun unknownSearchTypeThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", """{"query":"kotlin","search_type":"unknown"}""") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun invalidJsonThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", "not-json") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun cacheHitReturnsWithoutUpstreamOrQuota() = runTest {
        val cached = SearchResponse.newBuilder().setFormatted("缓存命中").build()
        coEvery { cache.getSearchResponse("search:kotlin") } returns cached

        val result = invoker.invoke(1L, "", """{"query":"kotlin"}""")

        assertEquals("缓存命中", result.formatted)
        coVerify(exactly = 0) { searchService.searchByType(any(), any(), any()) }
        coVerify(exactly = 0) { quotaManager.consume(QuotaCategory.SEARCH, any()) }
    }

    @Test
    fun missQueriesUpstreamAndWritesCache() = runTest {
        coEvery { cache.getSearchResponse("search:kotlin") } returns null
        coEvery { quotaManager.usagePercent(QuotaCategory.SEARCH) } returns 0
        coEvery { perUserQuotaStore.tryConsume(any(), QuotaCategory.SEARCH, 500, any()) } returns true
        coEvery { quotaManager.consume(QuotaCategory.SEARCH, 1) } returns true
        coEvery { searchService.searchByType("kotlin", SearchService.SearchType.SEARCH, 5) } returns
            SearchResponse.newBuilder().setFormatted("搜索结果（共 1 条）：kotlin 是 JVM 上的现代语言")
                .addItems(
                    SearchResultItem.newBuilder().setTitle("Kotlin").setSnippet("...")
                        .setUrl("https://kotlinlang.org").build()
                )
                .build()
        coEvery { cache.putSearchResponse(any(), any(), any()) } returns Unit

        val result = invoker.invoke(1L, "", """{"query":"kotlin"}""")

        // formatted 经 sanitize 包裹不可信数据区标记，核心业务文本保留
        assertTrue(result.formatted.contains("kotlin"))
        coVerify(exactly = 1) { searchService.searchByType("kotlin", SearchService.SearchType.SEARCH, 5) }
        coVerify(exactly = 1) { quotaManager.consume(QuotaCategory.SEARCH, 1) }
    }
}
