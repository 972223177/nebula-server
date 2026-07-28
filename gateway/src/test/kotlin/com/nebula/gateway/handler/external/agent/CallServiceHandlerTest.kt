package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.CallServiceRequest
import com.nebula.chat.external.CallServiceResponse
import com.nebula.chat.external.IpLocationResponse
import com.nebula.chat.external.SearchResponse
import com.nebula.chat.external.WeatherResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.ClientIpKey
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * CallServiceHandler 单元测试（Phase 10 通用调用）。
 *
 * 覆盖：method 路由值、按 service_id 路由到对应执行体、未知 id → NOT_FOUND、
 * 大小写不敏感路由、IP 定位公网 IP 正常路由。
 *
 * 注：参数缺失/非法 JSON → INVALID_PARAM 与非公网 IP 本地短路已下沉到 service 模块各
 * ExternalServiceInvoker，由对应的 Invoker 测试覆盖；本测试聚焦 Handler 的路由与透传。
 */
class CallServiceHandlerTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var handler: CallServiceHandler

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        handler = CallServiceHandler(
            ServiceRegistry(
                orchestrator,
                listOf(WeatherServiceProvider(), WebSearchServiceProvider(), GeoIpServiceProvider())
            )
        )
    }

    @Test
    fun methodShouldBeExternalCallService() {
        assertEquals("external/call_service", handler.method)
    }

    @Test
    fun handleShouldRouteWeatherAndReturnResultJson() = runTest {
        coEvery { orchestrator.invoke("weather", any(), any(), """{"city":"北京"}""") } returns
            WeatherResponse.newBuilder().setFormatted("北京 晴 25°C").build()
        val req = CallServiceRequest.newBuilder()
            .setServiceId("weather")
            .setParamsJson("""{"city":"北京"}""")
            .build()
        val resp: CallServiceResponse = withContext(ClientIpKey("203.0.113.7")) {
            withSession(DEFAULT_SESSION) { handler.handle(req) }
        }
        assertTrue(resp.resultJson.contains("北京 晴 25°C"))
    }

    @Test
    fun handleShouldThrowNotFoundForUnknownServiceId() = runTest {
        val req = CallServiceRequest.newBuilder().setServiceId("nope").setParamsJson("{}").build()
        val ex = assertFailsWith<BizException> {
            withContext(ClientIpKey("203.0.113.7")) {
                withSession(DEFAULT_SESSION) { handler.handle(req) }
            }
        }
        assertEquals(BizCode.NOT_FOUND, ex.bizCode)
    }

    @Test
    fun handleShouldRouteCaseInsensitiveServiceId() = runTest {
        // LLM 工具调用的大小写漂移（Web_Search）应能路由到 web_search
        coEvery { orchestrator.invoke("web_search", any(), any(), """{"query":"kotlin"}""") } returns
            SearchResponse.newBuilder().setFormatted("搜索结果").build()
        val req = CallServiceRequest.newBuilder()
            .setServiceId("Web_Search")
            .setParamsJson("""{"query":"kotlin"}""")
            .build()
        val resp: CallServiceResponse = withContext(ClientIpKey("203.0.113.7")) {
            withSession(DEFAULT_SESSION) { handler.handle(req) }
        }
        assertTrue(resp.resultJson.contains("搜索结果"))
    }

    @Test
    fun handleShouldRouteGeoIpForPublicIp() = runTest {
        coEvery { orchestrator.invoke("geo_ip", any(), "203.0.113.7", "{}") } returns IpLocationResponse.newBuilder()
            .setFormatted("中国 广东 深圳")
            .setCity("深圳市")
            .build()
        val req = CallServiceRequest.newBuilder().setServiceId("geo_ip").setParamsJson("{}").build()
        val resp: CallServiceResponse = withContext(ClientIpKey("203.0.113.7")) {
            withSession(DEFAULT_SESSION) { handler.handle(req) }
        }
        assertTrue(resp.resultJson.contains("深圳市"))
    }
}
