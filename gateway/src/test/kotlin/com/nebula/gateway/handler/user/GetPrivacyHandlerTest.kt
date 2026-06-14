package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GetPrivacyHandler 在线状态可见性读取 Handler 单元测试（BIZ-USER-06）。
 *
 * 覆盖场景：
 * - 读取隐藏状态：privacy 返回 true，验证响应中 hideOnlineStatus = true
 * - 读取可见状态：privacy 返回 false，验证响应中 hideOnlineStatus = false
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class GetPrivacyHandlerTest {

    private lateinit var userPrivacyService: UserPrivacyService
    private lateinit var handler: GetPrivacyHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setup() {
        userPrivacyService = mockk()
        handler = GetPrivacyHandler(userPrivacyService)
    }

    @Test
    fun getPrivacyShouldReturnHiddenStatus() = runTest {
        coEvery { userPrivacyService.getHideOnlineStatus(any(), any<GetPrivacyReq>()) } returns
                GetPrivacyResp.newBuilder().setHideOnlineStatus(true).build()

        val req = GetPrivacyReq.getDefaultInstance()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        assertTrue(result.hideOnlineStatus)
    }

    @Test
    fun getPrivacyShouldReturnVisibleStatus() = runTest {
        coEvery { userPrivacyService.getHideOnlineStatus(any(), any<GetPrivacyReq>()) } returns
                GetPrivacyResp.newBuilder().setHideOnlineStatus(false).build()

        val req = GetPrivacyReq.getDefaultInstance()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        assertFalse(result.hideOnlineStatus)
    }
}
