package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.IpLocationResponse
import com.nebula.chat.external.ListServicesRequest
import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem
import com.nebula.chat.external.WeatherResponse
import com.nebula.common.BizCode
import com.nebula.service.external.ExternalServiceOrchestrator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * ServiceRegistry 单元测试（Phase 10 服务发现+通用调用）。
 *
 * 覆盖：
 * - 内置 3 个服务描述（weather / web_search / geo_ip）被正确注册
 * - listDescriptors 的 category / ids / search_keyword 过滤
 * - versionHash 稳定且非空
 * - 各服务执行体 invoke 正确：经 orchestrator.invoke 路由、result_json 构造、错误语义透传
 *
 * 注：参数校验（缺参/非法 JSON）与非公网 IP 本地短路已下沉到 service 模块各 ExternalServiceInvoker，
 * 由对应的 WeatherInvokerTest / WebSearchInvokerTest / GeoIpInvokerTest 覆盖，本测试聚焦注册表与执行体路由。
 */
class ServiceRegistryTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var registry: ServiceRegistry

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        registry = ServiceRegistry(
            orchestrator,
            listOf(WeatherServiceProvider(), WebSearchServiceProvider(), GeoIpServiceProvider())
        )
    }

    @Test
    fun shouldRegisterThreeBuiltinServices() {
        val descriptors = registry.listDescriptors(ListServicesRequest.getDefaultInstance())
        assertEquals(3, descriptors.size)
        val ids = descriptors.map { it.id }.toSet()
        assertEquals(setOf("weather", "web_search", "geo_ip"), ids)
    }

    @Test
    fun listDescriptorsShouldFilterByCategory() {
        val req = ListServicesRequest.newBuilder().setCategory("weather").build()
        val descriptors = registry.listDescriptors(req)
        assertEquals(listOf("weather"), descriptors.map { it.id })
    }

    @Test
    fun listDescriptorsShouldFilterByIds() {
        val req = ListServicesRequest.newBuilder().addIds("weather").addIds("geo_ip").build()
        val descriptors = registry.listDescriptors(req).map { it.id }
        assertEquals(listOf("geo_ip", "weather"), descriptors) // 按 id 升序
    }

    @Test
    fun listDescriptorsShouldFilterBySearchKeyword() {
        val req = ListServicesRequest.newBuilder().setSearchKeyword("搜索").build()
        val descriptors = registry.listDescriptors(req).map { it.id }
        assertEquals(listOf("web_search"), descriptors)
    }

    @Test
    fun versionHashShouldBeStableAndNonEmpty() {
        val h1 = registry.versionHash()
        val h2 = registry.versionHash()
        assertTrue(h1.isNotBlank())
        assertEquals(64, h1.length) // SHA-256 十六进制
        assertEquals(h1, h2)
    }

    @Test
    fun allDescriptorsShouldBeLowRiskAndNoConsent() {
        registry.listDescriptors(ListServicesRequest.getDefaultInstance()).forEach {
            assertEquals("low", it.riskLevel)
            assertFalse(it.requiresUserConsent)
        }
    }

    @Test
    fun weatherInvokeShouldReturnFormattedResultJson() = runTest {
        coEvery { orchestrator.invoke("weather", any(), any(), """{"city":"北京"}""") } returns
            WeatherResponse.newBuilder().setFormatted("北京 晴 25°C").build()
        val def = registry.get("weather")!!
        val resultJson = def.invoke(1L, "203.0.113.7", """{"city":"北京"}""")
        val obj = Json.decodeFromString(JsonObject.serializer(), resultJson)
        assertEquals("北京 晴 25°C", (obj["formatted"] as JsonPrimitive).content)
    }

    @Test
    fun webSearchInvokeShouldReturnItemsResultJson() = runTest {
        coEvery { orchestrator.invoke("web_search", any(), any(), """{"query":"kotlin"}""") } returns SearchResponse.newBuilder()
            .setFormatted("搜索结果")
            .addItems(SearchResultItem.newBuilder().setTitle("Kotlin").setSnippet("...")
                .setUrl("https://kotlinlang.org").build())
            .build()
        val def = registry.get("web_search")!!
        val resultJson = def.invoke(1L, "203.0.113.7", """{"query":"kotlin"}""")
        val obj = Json.decodeFromString(JsonObject.serializer(), resultJson)
        assertEquals("搜索结果", (obj["formatted"] as JsonPrimitive).content)
        val items = obj["items"]
        assertTrue(items is JsonArray)
        assertEquals(1, (items as JsonArray).size)
    }

    @Test
    fun geoIpInvokeShouldReturnResultJsonForPublicIp() = runTest {
        coEvery { orchestrator.invoke("geo_ip", any(), "203.0.113.7", "{}") } returns IpLocationResponse.newBuilder()
            .setFormatted("中国 广东 深圳")
            .setCountry("中国")
            .setCity("深圳市")
            .setLatitude(22.54).setLongitude(114.06)
            .build()
        val def = registry.get("geo_ip")!!
        val resultJson = def.invoke(1L, "203.0.113.7", "{}")
        val obj = Json.decodeFromString(JsonObject.serializer(), resultJson)
        assertEquals("中国 广东 深圳", (obj["formatted"] as JsonPrimitive).content)
        assertEquals("深圳市", (obj["city"] as JsonPrimitive).content)
    }
}
