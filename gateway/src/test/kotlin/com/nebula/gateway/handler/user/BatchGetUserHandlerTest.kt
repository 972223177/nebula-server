package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.BatchGetUserResp
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BatchGetUserHandler 批量用户摘要查询 Handler 单元测试（BIZ-USER-03）。
 *
 * 覆盖场景：
 * - 批量获取用户：验证返回正确的 UserBrief 数量和字段
 * - 空列表：验证空输入返回空结果
 * - 部分 ID 不存在：验证只返回存在的用户（Review 修复：文档化此行为）
 */
class BatchGetUserHandlerTest {

    private lateinit var userService: UserService
    private lateinit var userRepository: UserRepository
    private lateinit var handler: BatchGetUserHandler

    @BeforeEach
    fun setup() {
        userService = mockk()
        userRepository = mockk()
        handler = BatchGetUserHandler(userService)
    }

    @Test
    fun `批量获取用户`() = runTest {
        val user1 = UserEntity(username = "user1", passwordHash = "hash1", nickname = "User One").apply {
            id = 1L
            avatar = "https://example.com/avatar1.jpg"
        }
        val user2 = UserEntity(username = "user2", passwordHash = "hash2", nickname = "User Two").apply {
            id = 2L
            avatar = "https://example.com/avatar2.jpg"
        }

        coEvery { userRepository.findAllById(listOf(1L, 2L)) } returns listOf(user1, user2)

        val req = BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L)).build()
        val resp = handler.handle(req)

        assertEquals(2, resp.usersList.size)
        assertEquals(1L, resp.getUsers(0).uid)
        assertEquals("user1", resp.getUsers(0).username)
        assertEquals("User One", resp.getUsers(0).displayName)
        assertEquals("https://example.com/avatar1.jpg", resp.getUsers(0).avatarUrl)
        assertEquals(2L, resp.getUsers(1).uid)
        assertEquals("user2", resp.getUsers(1).username)
    }

    @Test
    fun `空列表`() = runTest {
        coEvery { userRepository.findAllById(emptyList<Long>()) } returns emptyList()

        val req = BatchIdRequest.getDefaultInstance()
        val resp = handler.handle(req)

        assertEquals(0, resp.usersList.size)
    }

    @Test
    fun `部分ID不存在`() = runTest {
        val user1 = UserEntity(username = "user1", passwordHash = "hash1", nickname = "User One").apply {
            id = 1L
        }

        coEvery { userRepository.findAllById(listOf(1L, 999L)) } returns listOf(user1)

        val req = BatchIdRequest.newBuilder().addAllUids(listOf(1L, 999L)).build()
        val resp = handler.handle(req)

        // Review 修复：只返回存在的用户（ID=1），缺失 ID 静默跳过
        assertEquals(1, resp.usersList.size)
        assertEquals(1L, resp.getUsers(0).uid)
    }
}
