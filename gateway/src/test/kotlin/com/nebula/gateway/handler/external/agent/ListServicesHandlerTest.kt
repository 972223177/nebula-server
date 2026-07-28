package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.ListServicesRequest
import com.nebula.chat.external.ListServicesResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.external.agent.ServiceRegistry
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * ListServicesHandler 单元测试（Phase 10 服务发现）。
 *
 * 覆盖：method 路由值、需登录态、返回 3 个服务描述 + cacheTtlSeconds + versionHash。
 */
class ListServicesHandlerTest {

    private lateinit var registry: ServiceRegistry
    private lateinit var handler: ListServicesHandler

    @BeforeEach
    fun setup() {
        registry = ServiceRegistry(
            mockk(),
            listOf(WeatherServiceProvider(), WebSearchServiceProvider(), GeoIpServiceProvider())
        )
        handler = ListServicesHandler(registry)
    }

    @Test
    fun methodShouldBeExternalListServices() {
        assertEquals("external/list_services", handler.method)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val ex = assertFailsWith<BizException> {
            handler.handle(ListServicesRequest.getDefaultInstance())
        }
        assertEquals(BizCode.UNAUTHORIZED, ex.bizCode)
    }

    @Test
    fun handleShouldReturnDescriptorsWithCacheTtlAndVersionHash() = runTest {
        val resp: ListServicesResponse = withSession(DEFAULT_SESSION) {
            handler.handle(ListServicesRequest.getDefaultInstance())
        }
        assertEquals(3, resp.servicesCount)
        assertEquals(300, resp.cacheTtlSeconds)
        assertTrue(resp.versionHash.isNotBlank())
    }
}
