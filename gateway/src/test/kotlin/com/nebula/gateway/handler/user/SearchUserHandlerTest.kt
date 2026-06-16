package com.nebula.gateway.handler.user

import com.nebula.chat.user.SearchUserReq
import com.nebula.chat.user.SearchUserResp
import com.nebula.chat.user.UserBrief
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
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
    private lateinit var handler: SearchUserHandler

    @BeforeEach
    fun setUp() {
        userService = mockk()
        handler = SearchUserHandler(userService)
    }

    /** 创建测试用户摘要 */
    private fun createUserBrief(id: Long, username: String): UserBrief = UserBrief.newBuilder()
        .setUid(id)
        .setUsername(username)
        .setDisplayName("用户$id")
        .build()

    @Test
    fun searchShouldReturnResults() = runTest {
        val users = listOf(
            createUserBrief(1001L, "testuser1"),
            createUserBrief(1002L, "testuser2")
        )
        coEvery { userService.searchUsers(any(), any(), any()) } returns SearchUserResp.newBuilder()
            .addAllUsers(users)
            .setHasMore(false)
            .setNextCursor(0L)
            .build()

        val req = SearchUserReq.newBuilder()
            .setKeyword("test")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(2, resp.usersCount, "搜索结果应为 2 条")
        assertEquals("testuser1", resp.usersList[0].username)
        assertFalse(resp.hasMore, "2 条结果不超过 20 条限制，hasMore 应为 false")
    }

    @Test
    fun searchNoResultsShouldReturnEmpty() = runTest {
        coEvery { userService.searchUsers(any(), any(), any()) } returns SearchUserResp.newBuilder()
            .setHasMore(false)
            .setNextCursor(0L)
            .build()

        val req = SearchUserReq.newBuilder()
            .setKeyword("nonexistent")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(0, resp.usersCount, "无匹配结果")
        assertFalse(resp.hasMore, "无更多数据")
    }

    @Test
    fun paginationShouldLimitResults() = runTest {
        // 生成 21 条数据（limit=20，limit+1=21）
        val users = (1..21).map { i ->
            createUserBrief(1000L + i, "user$i")
        }
        coEvery { userService.searchUsers(any(), any(), any()) } returns SearchUserResp.newBuilder()
            .addAllUsers(users.take(20))
            .setHasMore(true)
            .setNextCursor(users.last().uid)
            .build()

        val req = SearchUserReq.newBuilder()
            .setKeyword("user")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(20, resp.usersCount, "应只返回 20 条")
        assertTrue(resp.hasMore, "实际有 21 条，hasMore 应为 true")
    }

    @Test
    fun cursorCorrectnessShouldReturnNextCursor() = runTest {
        val users = listOf(
            createUserBrief(1001L, "alpha"),
            createUserBrief(1002L, "beta")
        )
        coEvery { userService.searchUsers(any(), any(), any()) } returns SearchUserResp.newBuilder()
            .addAllUsers(users)
            .setHasMore(false)
            .setNextCursor(1002L)
            .build()

        val req = SearchUserReq.newBuilder()
            .setKeyword("user")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(2, resp.usersCount)
        assertEquals(1002L, resp.nextCursor)
        assertFalse(resp.hasMore)
    }

    @Test
    fun emptyKeywordShouldReturnEmpty() = runTest {
        coEvery { userService.searchUsers(any(), any(), any()) } returns SearchUserResp.newBuilder()
            .setHasMore(false)
            .setNextCursor(0L)
            .build()

        val req = SearchUserReq.newBuilder()
            .setKeyword("  ")
            .setCursor(0)
            .setLimit(20)
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(0, resp.usersCount, "空关键词应返回空结果")
        assertFalse(resp.hasMore)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = kotlin.test.assertFailsWith<com.nebula.common.exception.BizException> {
            val req = com.nebula.chat.user.SearchUserReq.getDefaultInstance()
            handler.handle(req)
        }
        kotlin.test.assertEquals(com.nebula.common.BizCode.UNAUTHORIZED, exception.bizCode, "无 Session 时应抛出 UNAUTHORIZED")
    }

}
