package com.nebula.gateway.handler.external

import com.nebula.chat.external.IpLocationRequest
import com.nebula.chat.external.IpLocationResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.ClientIpKey
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.GeoIpInvoker
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
        coEvery { orchestrator.invoke(GeoIpInvoker.SERVICE_ID, eq(userId), eq(clientIp), any()) } returns resp

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
     * 非公网 IP 的本地短路已下沉到 service 模块 GeoIpInvoker；此处验证 Handler 正确把 Invoker
     * （经模拟的 orchestrator）抛出的 SERVICE_UNAVAILABLE 透传给客户端，且确实委托了
     * orchestrator.invoke（不做重复短路、不浪费配额判定）。
     */
    @Test
    fun handleShouldPropagateServiceUnavailableFromOrchestrator() = runTest {
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
        // 模拟 Invoker 短路抛出的 SERVICE_UNAVAILABLE（真实短路逻辑由 GeoIpInvokerTest 覆盖）
        coEvery { orchestrator.invoke(GeoIpInvoker.SERVICE_ID, any(), any(), any()) } throws
            BizException(BizCode.SERVICE_UNAVAILABLE, "非公网 IP 无法地理定位（本地已短路，不调用上游）")
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
        // Handler 对每个非公网 IP 都委托了 orchestrator.invoke，且自身未重复短路
        coVerify(exactly = nonPublicIps.size) { orchestrator.invoke(GeoIpInvoker.SERVICE_ID, any(), any(), any()) }
    }

    /** 公网 IPv4 应正常委托 orchestrator.invoke（不被本地短路拦截）。 */
    @Test
    fun handleShouldDelegatePublicIpToOrchestrator() = runTest {
        val userId = DEFAULT_SESSION.userId
        val clientIp = "203.0.113.7"
        val resp = IpLocationResponse.newBuilder().setFormatted("中国 广东 深圳").build()
        coEvery { orchestrator.invoke(GeoIpInvoker.SERVICE_ID, eq(userId), eq(clientIp), any()) } returns resp

        val result = withContext(ClientIpKey(clientIp)) {
            withSession(DEFAULT_SESSION) {
                handler.handle(IpLocationRequest.newBuilder().build())
            }
        }
        assertEquals("中国 广东 深圳", result.formatted)
        coVerify(exactly = 1) { orchestrator.invoke(GeoIpInvoker.SERVICE_ID, eq(userId), eq(clientIp), any()) }
    }
}
