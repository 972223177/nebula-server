package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.SearchResultItem
import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.L1Config
import com.nebula.common.external.L2Config
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * ExternalServiceCache 搜索专用缓存方法（putSearchResponse / getSearchResponse）往返测试。
 *
 * 仅依赖 L1 本地内存层（命中优先于 L2）。Redis 连接使用 mock，并显式 stub
 * reactive.set/expire/get 返回已完成的 Mono，避免 Lettuce 协程 awaitOne 永久挂起；
 * 不引入真实依赖，避免测试 flaky。验证点：缓存命中后完整 SearchResponse（含 items）被保留。
 */
class ExternalServiceCacheTest {

    private fun buildCache(): ExternalServiceCache {
        val reactive = mockk<RedisReactiveCommands<String, String>>()
        val conn = mockk<StatefulRedisConnection<String, String>>()
        every { conn.reactive() } answers { reactive }
        // 让 L2 写入/读取立即返回，避免 awaitOne 在 mock 的 Mono 上永久挂起
        coEvery { reactive.set(any<String>(), any<String>()) } returns Mono.just("OK")
        coEvery { reactive.expire(any<String>(), any<Long>()) } returns Mono.just(true)
        coEvery { reactive.get(any<String>()) } returns Mono.empty()
        val config = ExternalServiceCacheConfig(
            l1 = L1Config(weatherTtlSeconds = 120, searchTtlSeconds = 300, maxEntries = 100),
            l2 = L2Config(
                keyPrefix = "ext:",
                weatherTtlSeconds = 1800,
                geoTtlSeconds = 86400,
                searchTtlSeconds = 3600,
                searchStableTtlSeconds = 21600,
                searchNewsTtlSeconds = 600
            )
        )
        return ExternalServiceCache(config, conn)
    }

    private fun sampleResponse(): SearchResponse = SearchResponse.newBuilder()
        .setFormatted("[外部数据]天气晴[/外部数据]")
        .addAllItems(
            listOf(
                SearchResultItem.newBuilder()
                    .setTitle("标题A")
                    .setSnippet("摘要A")
                    .setUrl("https://a.example")
                    .build(),
                SearchResultItem.newBuilder()
                    .setTitle("标题B")
                    .setSnippet("摘要B")
                    .setUrl("https://b.example")
                    .build()
            )
        )
        .build()

    @Test
    fun `putSearchResponse 后 getSearchResponse 应返回含 items 的完整响应`() = runTest {
        val cache = buildCache()
        val resp = sampleResponse()
        cache.putSearchResponse("search:测试", resp, 600_000L)

        val hit = cache.getSearchResponse("search:测试")
        assertNotNull(hit)
        assertEquals(resp.formatted, hit!!.formatted)
        assertEquals(2, hit.itemsCount)
        assertEquals("标题A", hit.getItems(0).title)
        assertEquals("https://b.example", hit.getItems(1).url)
    }

    @Test
    fun `未命中时 getSearchResponse 应返回 null`() = runTest {
        val cache = buildCache()
        assertNull(cache.getSearchResponse("search:不存在的键"))
    }

    @Test
    fun `损坏的缓存值反序列化失败应安全返回 null`() = runTest {
        val cache = buildCache()
        // 直接写入非 Base64/proto 的脏值（绕过 putSearchResponse 的编码），验证降级回源不抛异常
        cache.put("search:脏数据", "这不是合法的proto", QuotaCategory.SEARCH, 600_000L)
        assertNull(cache.getSearchResponse("search:脏数据"))
    }
}
