package com.nebula.gateway.dispatcher

import com.nebula.chat.user.BatchGetStatusResp
import com.nebula.chat.user.BatchGetUserResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.buildTestDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusData
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * User 批量查询集成冒烟测试。
 *
 * 通过 Dispatcher 测试完整的 request→dispatch→response 链路，
 * 覆盖 BatchGetUserHandler 和 BatchGetStatusHandler 两个 Handler 的端到端行为。
 *
 * 使用 [TestHelper] 提供的工具函数简化测试构建：
 * - [handlerEntry] 替代手写 ProtoCodec 逻辑
 * - [dispatchAs] 替代 `withContext + SessionKey + dispatch + requestEnvelope` 模式
 * - [buildTestDispatcher] 替代手写 Interceptor Pipeline
 */
class UserSmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var userService: UserService
    private lateinit var userRepo: UserRepository
    private lateinit var onlineStatusRepo: OnlineStatusRepository
    private lateinit var privacyRepo: PrivacyRepository
    private lateinit var sessionRegistry: SessionRegistry

    /** 测试用户 */
    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        userService = mockk()
        userRepo = mockk()
        onlineStatusRepo = mockk()
        privacyRepo = mockk()
        sessionRegistry = mockk()
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助：构建单 Handler Dispatcher
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建只注册一个 Handler 的 Dispatcher。
     *
     * @param handler Handler 实例
     * @param reqClass 请求类型
     * @param respClass 响应类型
     * @return 配置好的 Dispatcher
     */
    private fun <Req : Any, Resp : Any> singleHandlerDispatcher(
        handler: Handler<Req, Resp>,
        reqClass: kotlin.reflect.KClass<Req>,
        respClass: kotlin.reflect.KClass<Resp>
    ) = buildTestDispatcher(
        HandlerRegistry().apply { register(handlerEntry(handler, reqClass, respClass)) },
        session = session, sessionRegistry = sessionRegistry
    )

    // ===================================================================
    // 1. user/batchGet — 批量用户摘要查询
    // ===================================================================

    @Test
    fun `batchGet - 批量获取用户返回正确数量和字段`() = runTest {
        // Given
        val user1 = UserEntity(username = "user1", passwordHash = "hash1", nickname = "User One").apply {
            id = 1L
            avatar = "https://example.com/avatar1.jpg"
        }
        val user2 = UserEntity(username = "user2", passwordHash = "hash2", nickname = "User Two").apply {
            id = 2L
        }
        coEvery { userRepo.findAllById(listOf(1L, 2L)) } returns listOf(user1, user2)

        val dispatcher = singleHandlerDispatcher(
            BatchGetUserHandler(userService),
            BatchIdRequest::class, BatchGetUserResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGet",
            BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L)).build())

        // Then
        assertEquals(BizCode.OK.code, response.code, "批量查询应返回 200")
        val resp = BatchGetUserResp.parseFrom(response.result)
        assertEquals(2, resp.usersCount, "应有 2 个用户")
        assertEquals(1L, resp.getUsers(0).uid)
        assertEquals("user1", resp.getUsers(0).username)
        assertEquals("User One", resp.getUsers(0).displayName)
        assertEquals("https://example.com/avatar1.jpg", resp.getUsers(0).avatarUrl)
        assertEquals(2L, resp.getUsers(1).uid)
        assertEquals("user2", resp.getUsers(1).username)
    }

    @Test
    fun `batchGet - 空列表返回空结果`() = runTest {
        // Given
        coEvery { userRepo.findAllById(emptyList<Long>()) } returns emptyList()

        val dispatcher = singleHandlerDispatcher(
            BatchGetUserHandler(userService),
            BatchIdRequest::class, BatchGetUserResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGet",
            BatchIdRequest.getDefaultInstance())

        // Then
        assertEquals(BizCode.OK.code, response.code, "空列表应返回 200")
        assertEquals(0, BatchGetUserResp.parseFrom(response.result).usersCount, "应返回空列表")
    }

    @Test
    fun `batchGet - 部分ID不存在只返回存在的用户`() = runTest {
        // Given
        val user1 = UserEntity(username = "user1", passwordHash = "hash1", nickname = "User One").apply { id = 1L }
        coEvery { userRepo.findAllById(listOf(1L, 999L)) } returns listOf(user1)

        val dispatcher = singleHandlerDispatcher(
            BatchGetUserHandler(userService),
            BatchIdRequest::class, BatchGetUserResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGet",
            BatchIdRequest.newBuilder().addAllUids(listOf(1L, 999L)).build())

        // Then: 缺失 ID 静默跳过，只返回存在的用户
        assertEquals(BizCode.OK.code, response.code, "应返回 200")
        assertEquals(1, BatchGetUserResp.parseFrom(response.result).usersCount, "应只返回存在的用户")
        assertEquals(1L, BatchGetUserResp.parseFrom(response.result).getUsers(0).uid)
    }

    @Test
    fun `batchGet - 单个用户`() = runTest {
        // Given
        val user1 = UserEntity(username = "user1", passwordHash = "hash1", nickname = "User One").apply { id = 1L }
        coEvery { userRepo.findAllById(listOf(1L)) } returns listOf(user1)

        val dispatcher = singleHandlerDispatcher(
            BatchGetUserHandler(userService),
            BatchIdRequest::class, BatchGetUserResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGet",
            BatchIdRequest.newBuilder().addUids(1L).build())

        // Then
        assertEquals(BizCode.OK.code, response.code)
        assertEquals(1, BatchGetUserResp.parseFrom(response.result).usersCount)
    }

    // ===================================================================
    // 2. user/batchGetStatus — 批量在线状态查询
    // ===================================================================

    @Test
    fun `batchGetStatus - 无隐藏用户返回所有用户在线状态`() = runTest {
        // Given
        coEvery { privacyRepo.batchGetHideOnlineStatus(listOf(1L)) } returns emptySet()
        coEvery { onlineStatusRepo.getStatus(1L) } returns OnlineStatusData(status = 1, lastActiveAt = 0L)

        val dispatcher = singleHandlerDispatcher(
            BatchGetStatusHandler(onlineStatusRepo, privacyRepo),
            BatchIdRequest::class, BatchGetStatusResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGetStatus",
            BatchIdRequest.newBuilder().addUids(1L).build())

        // Then
        assertEquals(BizCode.OK.code, response.code, "状态查询应返回 200")
        val resp = BatchGetStatusResp.parseFrom(response.result)
        assertEquals(1, resp.statusesCount, "应有 1 个状态")
        assertEquals(1L, resp.getStatuses(0).uid)
        assertEquals(1, resp.getStatuses(0).status, "状态应为在线(1)")
    }

    @Test
    fun `batchGetStatus - 隐藏用户被过滤`() = runTest {
        // Given: uid=1 隐藏，uid=2 在线
        coEvery { privacyRepo.batchGetHideOnlineStatus(listOf(1L, 2L)) } returns setOf(1L)
        coEvery { onlineStatusRepo.getStatus(2L) } returns OnlineStatusData(status = 1, lastActiveAt = 0L)

        val dispatcher = singleHandlerDispatcher(
            BatchGetStatusHandler(onlineStatusRepo, privacyRepo),
            BatchIdRequest::class, BatchGetStatusResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGetStatus",
            BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L)).build())

        // Then: uid=1 被过滤，只有 uid=2
        assertEquals(BizCode.OK.code, response.code)
        val resp = BatchGetStatusResp.parseFrom(response.result)
        assertEquals(1, resp.statusesCount, "隐藏用户应被过滤")
        assertEquals(2L, resp.getStatuses(0).uid)
    }

    @Test
    fun `batchGetStatus - 混合状态`() = runTest {
        // Given: uid=1 隐藏，uid=2 在线，uid=3 离线
        coEvery { privacyRepo.batchGetHideOnlineStatus(listOf(1L, 2L, 3L)) } returns setOf(1L)
        coEvery { onlineStatusRepo.getStatus(2L) } returns OnlineStatusData(status = 1, lastActiveAt = 0L)
        coEvery { onlineStatusRepo.getStatus(3L) } returns null  // null = 离线

        val dispatcher = singleHandlerDispatcher(
            BatchGetStatusHandler(onlineStatusRepo, privacyRepo),
            BatchIdRequest::class, BatchGetStatusResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/batchGetStatus",
            BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L, 3L)).build())

        // Then
        assertEquals(BizCode.OK.code, response.code)
        val resp = BatchGetStatusResp.parseFrom(response.result)
        assertEquals(2, resp.statusesCount, "隐藏用户被过滤后应有 2 个")
        assertEquals(2L, resp.getStatuses(0).uid)
        assertEquals(1, resp.getStatuses(0).status, "用户2 在线")
        assertEquals(3L, resp.getStatuses(1).uid)
        assertEquals(0, resp.getStatuses(1).status, "用户3 离线")
    }
}
