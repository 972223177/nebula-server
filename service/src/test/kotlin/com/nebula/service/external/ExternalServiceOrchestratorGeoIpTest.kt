package com.nebula.service.external

import com.nebula.chat.external.IpLocationResponse
import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceQuotaConfig
import com.nebula.common.external.L1Config
import com.nebula.common.external.L2Config
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ExternalServiceOrchestrator IP 定位编排单元测试（external-service-backend.md 阶段 IP 定位）。
 *
 * 覆盖场景：
 * - 缓存命中直接返回，不调 GeoIpService / 不扣配额
 * - 缓存未命中：配额放行 → 调 GeoIpService → 写缓存
 * - x-forwarded-for 多级代理取首个 IP（normalizeIp）
 */
class ExternalServiceOrchestratorGeoIpTest {

    private val geoIpService = mockk<GeoIpService>()
    private val cache = mockk<ExternalServiceCache>()
    private val quotaManager = mockk<QuotaManager>()
    private val perUserQuotaStore = mockk<PerUserQuotaStore>()
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
    private val orchestrator = ExternalServiceOrchestrator(
        weatherService = mockk(),
        searchService = mockk(),
        geoIpService = geoIpService,
        quotaManager = quotaManager,
        cache = cache,
        perUserQuotaStore = perUserQuotaStore,
        quotaConfig = quotaConfig,
        cacheConfig = cacheConfig
    )

    @Test
    fun cacheHitReturnsWithoutUpstreamOrQuota() = runTest {
        val cached = IpLocationResponse.newBuilder().setFormatted("缓存命中").setCity("深圳市").build()
        coEvery { cache.getGeoIp("geoip:1.2.3.4") } returns cached

        val result = orchestrator.locateByIp(1001L, "1.2.3.4")

        assertEquals("缓存命中", result.formatted)
        assertEquals("深圳市", result.city)
        coVerify(exactly = 0) { geoIpService.locate(any()) }
        coVerify(exactly = 0) { quotaManager.consume(QuotaCategory.GEO, any()) }
    }

    @Test
    fun missQueriesUpstreamAndWritesCache() = runTest {
        coEvery { cache.getGeoIp("geoip:1.2.3.4") } returns null
        coEvery { quotaManager.usagePercent(QuotaCategory.GEO) } returns 0
        coEvery { perUserQuotaStore.tryConsume(any(), QuotaCategory.GEO, 50, any()) } returns true
        coEvery { quotaManager.consume(QuotaCategory.GEO, 1) } returns true
        coEvery { geoIpService.locate("1.2.3.4") } returns GeoIpService.GeoIpResult(
            country = "中国",
            region = "广东省",
            city = "深圳市",
            latitude = 22.54,
            longitude = 114.06,
            timezone = "Asia/Shanghai",
            isp = "China Telecom",
            formatted = "中国 广东 深圳（114.06,22.54）时区Asia/Shanghai 运营商China Telecom"
        )
        coEvery { cache.putGeoIp(any(), any(), any()) } returns Unit

        val result = orchestrator.locateByIp(1001L, "1.2.3.4")

        assertEquals("深圳市", result.city)
        assertEquals(22.54, result.latitude)
        assertEquals(114.06, result.longitude)
        coVerify(exactly = 1) { geoIpService.locate("1.2.3.4") }
        coVerify(exactly = 1) { quotaManager.consume(QuotaCategory.GEO, 1) }
    }

    @Test
    fun normalizesXForwardedForToFirstIp() = runTest {
        coEvery { cache.getGeoIp("geoip:1.2.3.4") } returns null
        coEvery { quotaManager.usagePercent(QuotaCategory.GEO) } returns 0
        coEvery { perUserQuotaStore.tryConsume(any(), QuotaCategory.GEO, 50, any()) } returns true
        coEvery { quotaManager.consume(QuotaCategory.GEO, 1) } returns true
        coEvery { geoIpService.locate("1.2.3.4") } returns GeoIpService.GeoIpResult(
            country = "", region = "", city = "", latitude = 1.0, longitude = 2.0,
            timezone = "", isp = "", formatted = "（1.00,2.00）"
        )
        coEvery { cache.putGeoIp(any(), any(), any()) } returns Unit

        orchestrator.locateByIp(1001L, "1.2.3.4, 9.9.9.9, 10.0.0.1")

        // 仅用首个（最原始客户端）IP 查询
        coVerify(exactly = 1) { geoIpService.locate("1.2.3.4") }
    }
}
