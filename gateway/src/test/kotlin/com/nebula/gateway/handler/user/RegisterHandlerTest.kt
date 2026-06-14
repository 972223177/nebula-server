package com.nebula.gateway.handler.user

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

/**
 * RegisterHandler 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 注册成功
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
    fun registerShouldReturnSuccess() = runTest {
        coEvery { userService.register(any()) } returns 10001L

        val req = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("password123")
            .setNickname("新用户")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(10001L, resp.uid, "注册成功应返回正确的 uid")
    }

    @Test
    fun registerUsernameExistsShouldThrowUsernameExists() = runTest {
        coEvery { userService.register(any()) } throws UserException(BizCode.USERNAME_EXISTS)

        val req = RegisterReq.newBuilder()
            .setUsername("existing")
            .setPassword("password123")
            .setNickname("新用户")
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
            .build()

        val e = assertFailsWith<UserException> {
            handler.handle(req)
        }
        assertEquals(BizCode.INVALID_PARAM, e.bizCode)
    }
}
