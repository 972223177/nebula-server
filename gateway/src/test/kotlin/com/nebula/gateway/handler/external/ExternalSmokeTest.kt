package com.nebula.gateway.handler.external

import com.nebula.chat.external.SearchRequest
import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.WeatherRequest
import com.nebula.chat.external.WeatherResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.testutil.createDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WeatherInvoker
import com.nebula.service.external.WebSearchInvoker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 外部服务 Handler 冒烟测试（external-service-backend.md 阶段 5）。
 *
 * 经完整 Dispatcher（Auth + Log + RateLimit + Exception 拦截器链）验证四场景：
 * - Handler 路由：external/query_weather、external/web_search 正确命中并委托编排层统一 invoke
 * - 缓存命中：编排层直接返回缓存内容（不抛错），响应透传回客户端
 * - 配额超限：编排层抛 QUOTA_EXCEEDED(1600) → Response.code=1600
 * - API 降级：编排层抛 SERVICE_UNAVAILABLE(1601) → Response.code=1601
 *
 * 编排层（ExternalServiceOrchestrator）以 mock 注入，聚焦 Handler/路由/异常映射。
 * 通用调用入口为 invoke(serviceId, userId, clientIp, paramsJson)，不再暴露 typed 方法。
 */
class ExternalSmokeTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var dispatcher: Dispatcher

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        dispatcher = createDispatcher(
            handlerEntry(
                QueryWeatherHandler(orchestrator),
                WeatherRequest::class,
                WeatherResponse::class
            ),
            handlerEntry(
                WebSearchHandler(orchestrator),
                SearchRequest::class,
                SearchResponse::class
            )
        )
    }

    @Test
    fun queryWeatherShouldRouteAndReturnResponse() = runTest {
        coEvery { orchestrator.invoke(WeatherInvoker.SERVICE_ID, any(), any(), any()) } returns
            WeatherResponse.newBuilder().setFormatted("Beijing 晴 25°C").build()

        val resp = dispatcher.dispatchAs(
            "external/query_weather",
            WeatherRequest.newBuilder().setCity("Beijing").build()
        )

        assertEquals(BizCode.OK.code, resp.code)
        assertEquals("Beijing 晴 25°C", WeatherResponse.parseFrom(resp.result).formatted)
    }

    @Test
    fun webSearchShouldRouteAndReturnResponse() = runTest {
        coEvery { orchestrator.invoke(WebSearchInvoker.SERVICE_ID, any(), any(), any()) } returns
            SearchResponse.newBuilder().setFormatted("搜索结果（共 3 条）").build()

        val resp = dispatcher.dispatchAs(
            "external/web_search",
            SearchRequest.newBuilder().setQuery("kotlin").setMaxResults(5).build()
        )

        assertEquals(BizCode.OK.code, resp.code)
        assertEquals("搜索结果（共 3 条）", SearchResponse.parseFrom(resp.result).formatted)
    }

    @Test
    fun queryWeatherCacheHitShouldReturnCachedFormatted() = runTest {
        // 编排层命中缓存：直接返回缓存内容，不抛错（代表未走上游 API）
        coEvery { orchestrator.invoke(WeatherInvoker.SERVICE_ID, any(), any(), any()) } returns
            WeatherResponse.newBuilder().setFormatted("[cached] Shanghai 多云 22°C").build()

        val resp = dispatcher.dispatchAs(
            "external/query_weather",
            WeatherRequest.newBuilder().setCity("Shanghai").build()
        )

        assertEquals(BizCode.OK.code, resp.code)
        assertEquals("[cached] Shanghai 多云 22°C", WeatherResponse.parseFrom(resp.result).formatted)
    }

    @Test
    fun queryWeatherQuotaExceededShouldReturn1600() = runTest {
        coEvery { orchestrator.invoke(any(), any(), any(), any()) } throws
            BizException(BizCode.QUOTA_EXCEEDED, "配额已用完，剩余 3600s 重置")

        val resp = dispatcher.dispatchAs(
            "external/query_weather",
            WeatherRequest.newBuilder().setCity("Beijing").build()
        )

        assertEquals(BizCode.QUOTA_EXCEEDED.code, resp.code)
        assertEquals("external/query_weather", resp.method)
    }

    @Test
    fun webSearchServiceUnavailableShouldReturn1601() = runTest {
        coEvery { orchestrator.invoke(any(), any(), any(), any()) } throws
            BizException(BizCode.SERVICE_UNAVAILABLE, "上游搜索服务暂不可用")

        val resp = dispatcher.dispatchAs(
            "external/web_search",
            SearchRequest.newBuilder().setQuery("kotlin").build()
        )

        assertEquals(BizCode.SERVICE_UNAVAILABLE.code, resp.code)
        assertEquals("external/web_search", resp.method)
    }
}
