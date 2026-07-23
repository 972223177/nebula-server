package com.nebula.gateway.handler.user

import com.nebula.chat.user.LogoutReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.EvictionReason
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * LogoutHandler 单元测试（AUTH-06）。
 *
 * 验证：
 * 1. 已登录会话调用 user/logout 返回 OK
 * 2. 调用期间主动 unregister 当前 Session token（token 失效 + 触发 eviction 关连接）
 */
class LogoutHandlerTest {

    private val sessionRegistry = mockk<SessionRegistry>(relaxed = true)
    private val handler = LogoutHandler(sessionRegistry)

    private val session = Session(
        userId = 1001L,
        token = "logout-token-abc",
        deviceType = "MOBILE",
        deviceId = "device-1",
        connectionId = "conn-1"
    )

    @Test
    fun logoutReturnsOkAndUnregistersToken() = runTest {
        val resp = withContext(SessionKey(session)) {
            handler.handle(LogoutReq.getDefaultInstance())
        }

        assertEquals(BizCode.OK.code, resp.code)
        assertEquals("user/logout", resp.method)
        coVerify(exactly = 1) { sessionRegistry.unregister("logout-token-abc", EvictionReason.LOGOUT) }
    }
}
