package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetProfileReq
import com.nebula.chat.user.GetProfileResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
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
    private lateinit var userRepository: UserRepository
    private lateinit var handler: GetProfileHandler

    @BeforeEach
    fun setup() {
        userService = mockk()
        userRepository = mockk()
        handler = GetProfileHandler(userService)
    }

    @Test
    fun `获取已有用户资料`() = runTest {
        val now = LocalDateTime.now()
        val entity = UserEntity(
            username = "testuser",
            passwordHash = "hash",
            nickname = "Test User"
        ).apply {
            id = 1001L
            avatar = "https://example.com/avatar.jpg"
            createdAt = now
        }

        coEvery { userRepository.findById(1001L) } returns java.util.Optional.of(entity)

        val req = GetProfileReq.newBuilder().setUid(1001L).build()
        val resp = handler.handle(req)

        assertEquals(1001L, resp.uid)
        assertEquals("testuser", resp.username)
        assertEquals("Test User", resp.displayName)
        assertEquals("https://example.com/avatar.jpg", resp.avatarUrl)
        assertEquals(now.atZone(ZoneOffset.UTC).toInstant().toEpochMilli(), resp.createdAt)
    }

    @Test
    fun `用户不存在`() = runTest {
        coEvery { userRepository.findById(9999L) } returns java.util.Optional.empty()

        val req = GetProfileReq.newBuilder().setUid(9999L).build()
        val exception = assertFailsWith<UserException> {
            handler.handle(req)
        }

        assertEquals(BizCode.USER_NOT_FOUND, exception.bizCode)
    }
}
