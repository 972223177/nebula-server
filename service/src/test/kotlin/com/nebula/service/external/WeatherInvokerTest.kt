package com.nebula.service.external

import com.nebula.chat.external.WeatherResponse
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
 * WeatherInvoker 单元测试 —— 覆盖天气执行体的参数校验（缺参/空白/非法 JSON）/
 * 缓存命中 / 上游调用 + 写缓存（estimate=8）。
 *
 * 与 GeoIpInvokerTest 同构：参数解析与校验逻辑已下沉到各 Invoker，执行体测试聚焦此处。
 * 每个测试在 [BeforeEach] 创建全新 mock，避免 coEvery 桩在用例间相互污染。
 */
class WeatherInvokerTest {

    private lateinit var weatherService: WeatherService
    private lateinit var cache: ExternalServiceCache
    private lateinit var quotaManager: QuotaManager
    private lateinit var perUserQuotaStore: PerUserQuotaStore
    private lateinit var pipeline: ExternalServicePipeline
    private lateinit var invoker: WeatherInvoker

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
        weatherService = mockk()
        cache = mockk()
        quotaManager = mockk()
        perUserQuotaStore = mockk()
        pipeline = ExternalServicePipeline(quotaManager, cache, perUserQuotaStore, quotaConfig, cacheConfig)
        invoker = WeatherInvoker(pipeline, weatherService)
    }

    @Test
    fun missingCityThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", "{}") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun blankCityThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", """{"city":""}""") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun invalidJsonThrowsInvalidParam() = runTest {
        val ex = assertFailsWith<BizException> { invoker.invoke(1L, "", "not-json") }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun cacheHitReturnsWithoutUpstreamOrQuota() = runTest {
        coEvery { cache.get("weather:北京") } returns "北京 晴 25°C"

        val result = invoker.invoke(1L, "", """{"city":"北京"}""")

        assertEquals("北京 晴 25°C", result.formatted)
        coVerify(exactly = 0) { weatherService.queryWeather(any()) }
        coVerify(exactly = 0) { quotaManager.consume(QuotaCategory.WEATHER, any()) }
    }

    @Test
    fun missQueriesUpstreamAndWritesCache() = runTest {
        coEvery { cache.get("weather:北京") } returns null
        coEvery { quotaManager.usagePercent(QuotaCategory.WEATHER) } returns 0
        coEvery { perUserQuotaStore.tryConsume(any(), QuotaCategory.WEATHER, 250, any()) } returns true
        coEvery { quotaManager.consume(QuotaCategory.WEATHER, 8) } returns true
        coEvery { weatherService.queryWeather("北京") } returns
            WeatherResponse.newBuilder().setFormatted("北京 晴 25°C").build()
        coEvery { cache.put(any(), any(), any(), any()) } returns Unit

        val result = invoker.invoke(1L, "", """{"city":"北京"}""")

        // formatted 经 sanitize 包裹不可信数据区标记，核心业务文本保留
        assertTrue(result.formatted.contains("北京"))
        coVerify(exactly = 1) { weatherService.queryWeather("北京") }
        coVerify(exactly = 1) { quotaManager.consume(QuotaCategory.WEATHER, 8) }
    }
}
