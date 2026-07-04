package com.nebula.gateway.handler.user

import com.nebula.chat.common.DeviceType
import com.nebula.chat.user.RegisterReq
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RegisterHandler 单元测试（D-23, D-24, D-25, CQ-13）。
 *
 * 覆盖场景：
 * - 注册成功（含 token）
 * - 用户名已存在
 * - 密码太短（3 位）
 * - 用户名为空
 */
class RegisterHandlerTest {

    private lateinit var userService: UserService
    private lateinit var handler: RegisterHandler

    @BeforeEach
    fun setUp() {
        userService = mockk()
        handler = RegisterHandler(userService)
    }

    @Test
    fun registerShouldReturnUidAndToken() = runTest {
        coEvery { userService.register(any()) } returns 10001L

        val req = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("password123")
            .setNickname("新用户")
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(10001L, resp.uid, "注册成功应返回正确的 uid")
        // CQ-13: 注册即登录，必须返回 token
        assertTrue(resp.token.isNotBlank(), "注册成功应返回非空 token")
        assertEquals(36, resp.token.length, "token 应为 UUID 格式（36字符）")
    }

    @Test
    fun registerUsernameExistsShouldThrowUsernameExists() = runTest {
        coEvery { userService.register(any()) } throws UserException(BizCode.USERNAME_EXISTS)

        val req = RegisterReq.newBuilder()
            .setUsername("existing")
            .setPassword("password123")
            .setNickname("新用户")
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()

        val e = assertFailsWith<UserException> {
            handler.handle(req)
        }
        assertEquals(BizCode.USERNAME_EXISTS, e.bizCode)
    }

    @Test
    fun registerPasswordTooShortShouldThrowInvalidParam() = runTest {
        coEvery { userService.register(any()) } throws UserException(BizCode.INVALID_PARAM)

        val req = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("abc")  // 3 位，少于 6 位
            .setNickname("新用户")
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()

        val e = assertFailsWith<UserException> {
            handler.handle(req)
        }
        assertEquals(BizCode.INVALID_PARAM, e.bizCode)
    }

    @Test
    fun registerEmptyUsernameShouldThrowInvalidParam() = runTest {
        coEvery { userService.register(any()) } throws UserException(BizCode.INVALID_PARAM)

        val req = RegisterReq.newBuilder()
            .setUsername("  ")  // 空白用户名
            .setPassword("password123")
            .setNickname("新用户")
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()

        val e = assertFailsWith<UserException> {
            handler.handle(req)
        }
        assertEquals(BizCode.INVALID_PARAM, e.bizCode)
    }
}
