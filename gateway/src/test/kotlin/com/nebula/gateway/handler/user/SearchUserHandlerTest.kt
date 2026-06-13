package com.nebula.gateway.handler.user

import com.nebula.chat.user.SearchUserReq
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import java.time.LocalDateTime
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SearchUserHandler 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 搜索成功
 * - 搜索无结果
 * - 分页测试（取 limit+1 条判断 has_more）
 * - 游标正确性
 * - 空关键词
 */
class SearchUserHandlerTest {

    private lateinit var userService: UserService
    private lateinit var userRepository: UserRepository
    private lateinit var handler: SearchUserHandler

    @BeforeEach
    fun setUp() {
        userService = mockk()
        userRepository = mockk<UserRepository>()
        handler = SearchUserHandler(userService)
    }

    /** 创建测试用户实体 */
    private fun createUser(id: Long, username: String, createdAt: LocalDateTime): UserEntity {
        return UserEntity(
            username = username,
            passwordHash = "hash",
            nickname = "用户$id",
            avatar = ""
        ).apply {
            this.id = id
            this.createdAt = createdAt
        }
    }

    @Test
    fun `搜索成功`() = runTest {
        val users = listOf(
            createUser(1001L, "testuser1", LocalDateTime.of(2026, 6, 1, 10, 0)),
            createUser(1002L, "testuser2", LocalDateTime.of(2026, 6, 1, 9, 0))
        )
        coEvery {
            userRepository.findByUsernameContaining("test", null, 21)
        } returns users

        val req = SearchUserReq.newBuilder()
            .setKeyword("test")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(2, resp.usersCount, "搜索结果应为 2 条")
        assertEquals("testuser1", resp.usersList[0].username)
        assertTrue(!resp.hasMore, "2 条结果不超过 20 条限制，hasMore 应为 false")
    }

    @Test
    fun `搜索无结果`() = runTest {
        coEvery {
            userRepository.findByUsernameContaining("nonexistent", null, 21)
        } returns emptyList()

        val req = SearchUserReq.newBuilder()
            .setKeyword("nonexistent")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(0, resp.usersCount, "无匹配结果")
        assertEquals(false, resp.hasMore, "无更多数据")
    }

    @Test
    fun `分页测试`() = runTest {
        // 生成 21 条数据（limit=20，limit+1=21）
        val users = (1..21).map { i ->
            createUser(
                1000L + i,
                "user$i",
                LocalDateTime.of(2026, 6, 1, 12, 0).minusHours(i.toLong())
            )
        }
        coEvery {
            userRepository.findByUsernameContaining("user", null, 21)
        } returns users

        val req = SearchUserReq.newBuilder()
            .setKeyword("user")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(20, resp.usersCount, "应只返回 20 条")
        assertEquals(true, resp.hasMore, "实际有 21 条，hasMore 应为 true")
    }

    @Test
    fun `游标正确性`() = runTest {
        val baseTime = LocalDateTime.of(2026, 6, 1, 12, 0)
        val users = listOf(
            createUser(1001L, "alpha", baseTime.minusHours(2)),
            createUser(1002L, "beta", baseTime.minusHours(3))
        )
        coEvery {
            userRepository.findByUsernameContaining("user", null, 21)
        } returns users

        val req = SearchUserReq.newBuilder()
            .setKeyword("user")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(2, resp.usersCount)
        // next_cursor 应为最后一条的 createdAt 毫秒时间戳
        val expectedCursor = users.last().createdAt!!.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedCursor, resp.nextCursor)
        assertEquals(false, resp.hasMore)
    }

    @Test
    fun `空关键词返回空结果`() = runTest {
        val req = SearchUserReq.newBuilder()
            .setKeyword("  ")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(0, resp.usersCount, "空关键词应返回空结果")
        assertEquals(false, resp.hasMore)
    }
}
