package com.nebula.gateway.handler.user

import com.nebula.chat.Response
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.redis.PrivacyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * SetPrivacyHandler 在线状态可见性设置 Handler 单元测试（BIZ-USER-05）。
 *
 * 覆盖场景：
 * - 设置隐藏在线状态：验证 setHideOnlineStatus 被正确调用
 * - 设置可见：验证 setHideOnlineStatus(false) 被正确调用
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class SetPrivacyHandlerTest {

    private lateinit var privacyRepository: PrivacyRepository
    private lateinit var handler: SetPrivacyHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setup() {
        privacyRepository = mockk()
        handler = SetPrivacyHandler(privacyRepository)
    }

    @Test
    fun `设置隐藏在线状态`() = runTest {
        coEvery { privacyRepository.setHideOnlineStatus(1001L, true) } returns Unit

        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        coVerify(exactly = 1) { privacyRepository.setHideOnlineStatus(1001L, true) }
        assertEquals(BizCode.OK.code, result.code)
        assertEquals("user/setPrivacy", result.method)
    }

    @Test
    fun `设置可见`() = runTest {
        coEvery { privacyRepository.setHideOnlineStatus(1001L, false) } returns Unit

        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        coVerify(exactly = 1) { privacyRepository.setHideOnlineStatus(1001L, false) }
        assertEquals(BizCode.OK.code, result.code)
    }
}
