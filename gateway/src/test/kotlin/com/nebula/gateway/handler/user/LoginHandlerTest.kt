package com.nebula.gateway.handler.user

import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LoginHandler 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 密码登录成功
 * - 密码错误
 * - 用户名不存在
 * - Token 重连成功且复用 Token（Review 修复验证）
 * - Token 过期回退到密码验证
 */
class LoginHandlerTest {

    private lateinit var userService: UserService
    private lateinit var userRepository: UserRepository
    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var handler: LoginHandler

    private val existingUser = UserEntity(
        username = "testuser",
        passwordHash = "hashed-password",
        nickname = "测试用户",
        avatar = ""
    ).apply { id = 1001L }

    @BeforeEach
    fun setUp() {
        userService = mockk()
        userRepository = mockk<UserRepository>()
        sessionRegistry = mockk<SessionRegistry>()
        handler = LoginHandler(userService, sessionRegistry)
    }

    @Test
    fun `密码登录成功`() = runTest {
        coEvery { userService.loginByPassword(any()) } returns 1001L

        val req = LoginReq.newBuilder()
            .setUsername("testuser")
            .setPassword("correct-password")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(1001L, resp.uid)
        assertTrue(resp.token.isNotBlank(), "登录成功后应返回 Token")
        assertTrue(resp.serverNow > 0, "应返回服务端时间戳")
    }

    @Test
    fun `密码错误`() = runTest {
        coEvery { userService.loginByPassword(any()) } throws UserException(BizCode.AUTH_FAILED)

        val req = LoginReq.newBuilder()
            .setUsername("testuser")
            .setPassword("wrong-password")
            .build()

        try {
            handler.handle(req)
            kotlin.test.fail("应抛出 UserException(AUTH_FAILED)")
        } catch (e: UserException) {
            assertEquals(BizCode.AUTH_FAILED, e.bizCode)
        }
    }

    @Test
    fun `用户名不存在`() = runTest {
        coEvery { userService.loginByPassword(any()) } throws UserException(BizCode.USER_NOT_FOUND)

        val req = LoginReq.newBuilder()
            .setUsername("nonexistent")
            .setPassword("some-password")
            .build()

        try {
            handler.handle(req)
            kotlin.test.fail("应抛出 UserException(USER_NOT_FOUND)")
        } catch (e: UserException) {
            assertEquals(BizCode.USER_NOT_FOUND, e.bizCode)
        }
    }

    @Test
    fun `Token 重连成功且复用 Token`() = runTest {
        val existingSession = Session(
            userId = 1001L,
            token = "existing-token-abc",
            deviceType = "MOBILE",
            deviceId = "device-001",
            connectionId = "conn-001"
        )
        coEvery { sessionRegistry.validate("existing-token-abc") } returns existingSession
        coEvery { userRepository.findById(1001L) } returns Optional.of(existingUser)

        val req = LoginReq.newBuilder()
            .setToken("existing-token-abc")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        // Review 修复验证：Token 重连时应复用现有 Token
        assertEquals("existing-token-abc", resp.token, "Token 重连应复用现有 Token，不生成新 Token")
        assertEquals(1001L, resp.uid)
    }

    @Test
    fun `Token 过期回退到密码验证`() = runTest {
        coEvery { sessionRegistry.validate("expired-token") } returns null
        coEvery { userService.loginByPassword(any()) } returns 1001L

        val req = LoginReq.newBuilder()
            .setToken("expired-token")
            .setUsername("testuser")
            .setPassword("correct-password")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(1001L, resp.uid)
        assertTrue(resp.token.isNotBlank(), "Token 过期后应重新生成 Token")
    }
}
