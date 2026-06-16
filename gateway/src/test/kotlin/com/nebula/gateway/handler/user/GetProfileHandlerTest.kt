package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetProfileReq
import com.nebula.chat.user.GetProfileResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GetProfileHandler 用户详细资料查询 Handler 单元测试。
 *
 * 覆盖场景：
 * - 获取已有用户资料：验证返回正确的 uid、username、displayName、avatarUrl、createdAt
 * - 用户不存在：抛出 UserException(USER_NOT_FOUND)
 */
class GetProfileHandlerTest {

    private lateinit var userService: UserService
    private lateinit var handler: GetProfileHandler

    @BeforeEach
    fun setup() {
        userService = mockk()
        handler = GetProfileHandler(userService)
    }

    @Test
    fun getProfileShouldReturnUserProfile() = runTest {
        coEvery { userService.getProfile(any()) } returns GetProfileResp.newBuilder()
            .setUid(1001L)
            .setUsername("testuser")
            .setDisplayName("Test User")
            .setAvatarUrl("https://example.com/avatar.jpg")
            .setCreatedAt(1700000000000L)
            .build()

        val req = GetProfileReq.newBuilder().setUid(1001L).build()
        val resp = handler.handle(req)

        assertEquals(1001L, resp.uid)
        assertEquals("testuser", resp.username)
        assertEquals("Test User", resp.displayName)
        assertEquals("https://example.com/avatar.jpg", resp.avatarUrl)
        assertEquals(1700000000000L, resp.createdAt)
    }

    @Test
    fun getProfileUserNotFoundShouldThrowUserNotFound() = runTest {
        coEvery { userService.getProfile(any()) } throws UserException(BizCode.USER_NOT_FOUND)

        val req = GetProfileReq.newBuilder().setUid(9999L).build()
        val exception = assertFailsWith<UserException> {
            handler.handle(req)
        }

        assertEquals(BizCode.USER_NOT_FOUND, exception.bizCode)
    }
}
