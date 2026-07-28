package com.nebula.service.external

import com.nebula.chat.external.IpLocationResponse
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
 * GeoIpInvoker 单元测试 —— 覆盖 IP 定位执行体的本地短路（非公网 IP）/ 缓存命中 /
 * 上游调用 + 写缓存 / x-forwarded-for 取首个 IP（normalizeIp）。
 *
 * 原 ExternalServiceOrchestrator.locateByIp 的逻辑已下沉到本 Invoker，测试随之迁移。
 * 每个测试在 [BeforeEach] 创建全新 mock，避免 coEvery 桩在用例间相互污染。
 */
class GeoIpInvokerTest {

    private lateinit var geoIpService: GeoIpService
    private lateinit var cache: ExternalServiceCache
    private lateinit var quotaManager: QuotaManager
    private lateinit var perUserQuotaStore: PerUserQuotaStore
    private lateinit var pipeline: ExternalServicePipeline
    private lateinit var invoker: GeoIpInvoker

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
        geoIpService = mockk()
        cache = mockk()
        quotaManager = mockk()
        perUserQuotaStore = mockk()
        pipeline = ExternalServicePipeline(quotaManager, cache, perUserQuotaStore, quotaConfig, cacheConfig)
        invoker = GeoIpInvoker(pipeline, geoIpService)
    }

    @Test
    fun cacheHitReturnsWithoutUpstreamOrQuota() = runTest {
        val cached = IpLocationResponse.newBuilder().setFormatted("缓存命中").setCity("深圳市").build()
        coEvery { cache.getGeoIp("geoip:1.2.3.4") } returns cached

        val result = invoker.invoke(1001L, "1.2.3.4", "{}")

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

        val result = invoker.invoke(1001L, "1.2.3.4", "{}")

        assertEquals("深圳市", result.city)
        assertEquals(22.54, result.latitude)
        assertEquals(114.06, result.longitude)
        // formatted 经 sanitize 包裹不可信数据区标记，核心业务文本保留（上游 formatted 含「深圳」）
        assertTrue(result.formatted.contains("深圳"))
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

        invoker.invoke(1001L, "1.2.3.4, 9.9.9.9, 10.0.0.1", "{}")

        // 仅用首个（最原始客户端）IP 查询
        coVerify(exactly = 1) { geoIpService.locate("1.2.3.4") }
    }

    /**
     * 非公网 IP 本地短路：在调用上游前经 isPublicUnicastIpv4 判定，直接抛 SERVICE_UNAVAILABLE，
     * 不发起上游调用、不消耗配额（D-XX）。覆盖私有/回环/链路本地/CGNAT/多播/保留/IPv6/非法格式。
     */
    @Test
    fun nonPublicIpShortCircuitsWithoutUpstream() = runTest {
        val nonPublicIps = listOf(
            "127.0.0.1",        // 回环
            "10.0.0.1",         // 私有 A
            "172.16.0.1",       // 私有 B
            "192.168.1.5",      // 私有 C
            "169.254.0.1",      // 链路本地
            "100.64.0.1",       // CGNAT
            "0.0.0.0",          // 本网络
            "224.0.0.1",        // 多播
            "240.0.0.1",        // 保留
            "::1",              // IPv6 回环
            "not-an-ip"         // 非法格式
        )
        // 短路发生在 pipeline.run 之前，pipeline/cache/quota 均不应被调用
        for (ip in nonPublicIps) {
            val ex = assertFailsWith<BizException> { invoker.invoke(1001L, ip, "{}") }
            assertEquals(BizCode.SERVICE_UNAVAILABLE, ex.bizCode)
        }
        coVerify(exactly = 0) { geoIpService.locate(any()) }
        coVerify(exactly = 0) { quotaManager.consume(any(), any()) }
    }
}
