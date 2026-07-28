package com.nebula.gateway.handler.external

import com.nebula.chat.external.WeatherRequest
import com.nebula.chat.external.WeatherResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WeatherInvoker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * QueryWeatherHandler 单元测试（external-service-backend.md 阶段 4/5）。
 *
 * 覆盖场景：
 * - 正常委托：Handler 取登录态 userId 并委托 [ExternalServiceOrchestrator.queryWeather]
 * - method 路由值："external/query_weather"
 * - 无 Session 上下文时抛 [BizException](UNAUTHORIZED)
 */
class QueryWeatherHandlerTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var handler: QueryWeatherHandler

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        handler = QueryWeatherHandler(orchestrator)
    }

    @Test
    fun methodShouldBeExternalQueryWeather() {
        assertEquals("external/query_weather", handler.method)
    }

    @Test
    fun handleShouldDelegateToOrchestratorWithSessionUserId() = runTest {
        val userId = DEFAULT_SESSION.userId
        val resp = WeatherResponse.newBuilder()
            .setFormatted("北京 晴 25°C")
            .build()
        coEvery { orchestrator.invoke(WeatherInvoker.SERVICE_ID, eq(userId), any(), any()) } returns resp

        val result = withSession(DEFAULT_SESSION) {
            handler.handle(WeatherRequest.newBuilder().setCity("北京").build())
        }

        assertEquals("北京 晴 25°C", result.formatted)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = assertFailsWith<BizException> {
            handler.handle(WeatherRequest.newBuilder().setCity("北京").build())
        }
        assertEquals(BizCode.UNAUTHORIZED, exception.bizCode)
    }
}
