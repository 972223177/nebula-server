package com.nebula.gateway.handler.external

import com.nebula.chat.external.IpLocationRequest
import com.nebula.chat.external.IpLocationResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.ClientIpKey
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * IpLocationHandler 单元测试（external-service-backend.md 阶段 IP 定位）。
 *
 * 覆盖场景：
 * - 正常委托：Handler 取登录态 userId 与客户端 IP（requireClientIp）并委托 orchestrator.locateByIp
 * - method 路由值："external/geo_ip"
 * - 无 Session 上下文时抛 BizException(UNAUTHORIZED)
 * - 无 ClientIp 上下文时抛 BizException(SERVICE_UNAVAILABLE)
 */
class IpLocationHandlerTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var handler: IpLocationHandler

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        handler = IpLocationHandler(orchestrator)
    }

    @Test
    fun methodShouldBeExternalGeoIp() {
        assertEquals("external/geo_ip", handler.method)
    }

    @Test
    fun handleShouldDelegateToOrchestratorWithSessionUserIdAndClientIp() = runTest {
        val userId = DEFAULT_SESSION.userId
        val clientIp = "203.0.113.7"
        val resp = IpLocationResponse.newBuilder()
            .setFormatted("中国 广东 深圳（114.06,22.54）时区Asia/Shanghai 运营商China Telecom")
            .build()
        coEvery { orchestrator.locateByIp(userId, clientIp) } returns resp

        val result = withContext(ClientIpKey(clientIp)) {
            withSession(DEFAULT_SESSION) {
                handler.handle(IpLocationRequest.newBuilder().build())
            }
        }

        assertEquals("中国 广东 深圳（114.06,22.54）时区Asia/Shanghai 运营商China Telecom", result.formatted)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = assertFailsWith<BizException> {
            withContext(ClientIpKey("1.2.3.4")) {
                handler.handle(IpLocationRequest.newBuilder().build())
            }
        }
        assertEquals(BizCode.UNAUTHORIZED, exception.bizCode)
    }

    @Test
    fun handleShouldRequireClientIp() = runTest {
        val exception = assertFailsWith<BizException> {
            withSession(DEFAULT_SESSION) {
                handler.handle(IpLocationRequest.newBuilder().build())
            }
        }
        assertEquals(BizCode.SERVICE_UNAVAILABLE, exception.bizCode)
    }

    /**
     * 非公网 IPv4（私有/回环/链路本地/保留段）应在本地短路，直接抛 SERVICE_UNAVAILABLE，
     * 且**完全不调用** orchestrator（不浪费上游配额、不写失败缓存）。
     */
    @Test
    fun handleShouldRejectNonPublicIpLocallyWithoutCallingOrchestrator() = runTest {
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
        for (ip in nonPublicIps) {
            val exception = assertFailsWith<BizException> {
                withContext(ClientIpKey(ip)) {
                    withSession(DEFAULT_SESSION) {
                        handler.handle(IpLocationRequest.newBuilder().build())
                    }
                }
            }
            assertEquals(BizCode.SERVICE_UNAVAILABLE, exception.bizCode)
        }
        coVerify(exactly = 0) { orchestrator.locateByIp(any(), any()) }
    }

    /** 公网 IPv4 应正常委托 orchestrator（不被本地短路拦截）。 */
    @Test
    fun handleShouldDelegatePublicIpToOrchestrator() = runTest {
        val userId = DEFAULT_SESSION.userId
        val clientIp = "203.0.113.7"
        val resp = IpLocationResponse.newBuilder().setFormatted("中国 广东 深圳").build()
        coEvery { orchestrator.locateByIp(userId, clientIp) } returns resp

        val result = withContext(ClientIpKey(clientIp)) {
            withSession(DEFAULT_SESSION) {
                handler.handle(IpLocationRequest.newBuilder().build())
            }
        }
        assertEquals("中国 广东 深圳", result.formatted)
        coVerify(exactly = 1) { orchestrator.locateByIp(userId, clientIp) }
    }
}
