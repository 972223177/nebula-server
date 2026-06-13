package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.chat.conversation.ConvListReq
import com.nebula.chat.conversation.ConvListResp
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.CreateGroupResp
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.GroupMembersResp
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.common.BizCode
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.UserRepository
import com.google.protobuf.ByteString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.time.LocalDateTime
import java.util.Optional
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 Conversation 集成冒烟测试。
 *
 * 通过 Dispatcher 直接测试完整的 request→dispatch→response 链路，
 * 覆盖 Phase 7 全部 7 个 Conversation Handler 的端到端行为。
 *
 * 测试模式参考 [PipelineIntegrationTest]，Mock 所有外部依赖（Repository、PushService 等），
 * 通过 Dispatcher + Interceptor Pipeline 模拟真实请求处理流程。
 */
class ConversationSmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var sessionRegistry: SessionRegistry

    /** 测试用户 Session（userId=1001，群主） */
    private val ownerSession = Session(1001L, "token-owner", "MOBILE", "dev-1", "conn-1")
    /** 测试用户 Session（userId=2001，普通成员） */
    private val memberSession = Session(2001L, "token-member", "MOBILE", "dev-2", "conn-2")
    /** 测试用户 Session（userId=3001，非成员） */
    private val outsiderSession = Session(3001L, "token-outsider", "MOBILE", "dev-3", "conn-3")

    /** 测试群 ID */
    private val testConvId = "550e8400-e29b-41d4-a716-446655440001"
    /** 测试群名称 */
    private val testGroupName = "测试群聊"

    // ========== 测试数据实体 ==========

    private fun testConversation() = ConversationEntity(
        type = 2,  // group
        name = testGroupName,
        avatar = "",
        groupOwnerUid = 1001L,
        memberCount = 3,
        maxMembers = 200,
        status = 0  // active
    ).apply {
        id = testConvId
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    private fun ownerMember() = ConversationMemberEntity(
        conversationId = testConvId,
        userId = 1001L,
        role = "owner"
    ).apply {
        id = 1L
        joinedAt = LocalDateTime.now()
    }

    private fun regularMember(userId: Long = 2001L) = ConversationMemberEntity(
        conversationId = testConvId,
        userId = userId,
        role = "member"
    ).apply {
        id = userId
        joinedAt = LocalDateTime.now()
    }

    private fun testUser(userId: Long, username: String, nickname: String) = UserEntity(
        username = username,
        passwordHash = "hash",
        nickname = nickname
    ).apply { id = userId }

    @BeforeEach
    fun setUp() {
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        lockManager = mockk()
        transactionTemplate = mockk()
        pushService = mockk(relaxed = true)
        sessionRegistry = mockk()

        // Mock 锁管理器：直接执行代码块
        coEvery { lockManager.withLock(any(), any<suspend () -> kotlin.Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as suspend () -> kotlin.Any).invoke()
        }

        // Mock 事务模板：直接执行回调（跳过真实事务）
        every { transactionTemplate.execute<Any?>(any()) } answers {
            val callback = args[0] as org.springframework.transaction.support.TransactionCallback<Any?>
            callback.doInTransaction(mockk(relaxed = true))
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 构建 Dispatcher，注册指定 Handler 并配置 Interceptor Pipeline。
     *
     * 自定义 AuthInterceptor 提取固定 token，通过 [session] 模拟认证用户。
     */
    private fun buildDispatcher(
        vararg entries: HandlerEntry,
        session: Session = ownerSession
    ): Dispatcher {
        val registry = HandlerRegistry()
        entries.forEach { registry.register(it) }

        val authInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping")
        ) {
            override fun extractToken(request: Request): String? = session.token
        }
        coEvery { sessionRegistry.validate(session.token) } returns session

        val interceptors = listOf(
            authInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        return Dispatcher(registry, interceptors)
    }

    /** 构建 HandlerEntry */
    private fun <Req : Any, Resp : Any> entry(
        handler: com.nebula.gateway.handler.Handler<Req, Resp>,
        reqClass: kotlin.reflect.KClass<Req>,
        respClass: kotlin.reflect.KClass<Resp>
    ): HandlerEntry {
        val reqCodec = ProtoCodec.buildCodec(reqClass)
        val respCodec = ProtoCodec.buildCodec(respClass)
        return HandlerEntry(
            handler = handler,
            reqClass = reqClass,
            respClass = respClass,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )
    }

    /** 构建 Request Envelope */
    private fun request(method: String, params: ByteString = ByteString.EMPTY): Request =
        Request.newBuilder().setMethod(method).setParams(params).build()

    // ===================================================================
    // 冒烟测试用例
    // ===================================================================

    // ---------- conversation/list ----------

    @Test
    fun `conversation list - 空列表返回200`() = runTest {
        coEvery {
            conversationRepository.findConversationsByUserId(1001L, null, any<PageRequest>())
        } returns emptyList()

        val handler = ListConversationsHandler(conversationRepository, conversationMemberRepository)
        val dispatcher = buildDispatcher(entry(handler, ConvListReq::class, ConvListResp::class))

        val req = ConvListReq.newBuilder().setLimit(20).build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/list", req.toByteString()))
        }

        assertEquals(200, response.code, "空会话列表应返回 200")
        val resp = ConvListResp.parseFrom(response.result)
        assertEquals(0, resp.conversationsCount, "空列表应有 0 个会话")
        assertEquals(false, resp.hasMore, "空列表 hasMore 应为 false")
    }

    @Test
    fun `conversation list - 有会话返回列表`() = runTest {
        val conv = testConversation()
        coEvery {
            conversationRepository.findConversationsByUserId(1001L, null, any<PageRequest>())
        } returns listOf(conv)

        // Mock 批量获取 lastReadMsgId
        coEvery {
            conversationMemberRepository.findByConversationIdsAndUserId(any(), 1001L)
        } returns listOf(ownerMember())

        val handler = ListConversationsHandler(conversationRepository, conversationMemberRepository)
        val dispatcher = buildDispatcher(entry(handler, ConvListReq::class, ConvListResp::class))

        val req = ConvListReq.newBuilder().setLimit(20).build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/list", req.toByteString()))
        }

        assertEquals(200, response.code, "会话列表应返回 200")
        val resp = ConvListResp.parseFrom(response.result)
        assertEquals(1, resp.conversationsCount, "应有 1 个会话")
    }

    // ---------- conversation/create_group ----------

    @Test
    fun `create group - 正常创建返回conversationId`() = runTest {
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val handler = CreateGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, CreateGroupReq::class, CreateGroupResp::class))

        val req = CreateGroupReq.newBuilder()
            .setName("新测试群")
            .addAllMemberUids(listOf(2001L, 3001L))
            .build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/create_group", req.toByteString()))
        }

        assertEquals(200, response.code, "创建群聊应返回 200")
        val resp = CreateGroupResp.parseFrom(response.result)
        assertTrue(resp.conversationId.isNotEmpty(), "conversationId 不应为空")
        assertEquals("新测试群", resp.name, "群名称应匹配")

        // 验证推送 GROUP_CREATED
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `create group - name为空返回非200`() = runTest {
        val handler = CreateGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, CreateGroupReq::class, CreateGroupResp::class))

        val req = CreateGroupReq.newBuilder().setName("").build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/create_group", req.toByteString()))
        }

        assertTrue(response.code != 200, "空名称应返回非 200 错误码")
    }

    @Test
    fun `create group - 成员数超上限返回GROUP_FULL`() = runTest {
        val handler = CreateGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, CreateGroupReq::class, CreateGroupResp::class))

        // 201 个成员（含创建者 = 202 > 200 上限）
        val tooManyMembers = (2001L..2201L).toList()
        val req = CreateGroupReq.newBuilder()
            .setName("超大群")
            .addAllMemberUids(tooManyMembers)
            .build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/create_group", req.toByteString()))
        }

        assertEquals(BizCode.GROUP_FULL.code, response.code, "超上限应返回 GROUP_FULL")
    }

    // ---------- conversation/group_members ----------

    @Test
    fun `group members - 成员可查看成员列表`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)

        val owner = ownerMember()
        val member1 = regularMember(2001L)
        val member2 = regularMember(3001L)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        coEvery { conversationMemberRepository.findByConversationId(testConvId) } returns listOf(owner, member1, member2)

        // Mock UserRepository 批量查询
        val user1 = testUser(1001L, "owner", "群主")
        val user2 = testUser(2001L, "member1", "成员1")
        val user3 = testUser(3001L, "member2", "成员2")
        every { userRepository.findAllById(listOf(1001L, 2001L, 3001L)) } returns listOf(user1, user2, user3)

        val handler = GroupMembersHandler(conversationMemberRepository, userRepository)
        val dispatcher = buildDispatcher(entry(handler, GroupMembersReq::class, GroupMembersResp::class))

        val req = GroupMembersReq.newBuilder().setConversationId(testConvId).build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/group_members", req.toByteString()))
        }

        assertEquals(200, response.code, "成员查看列表应返回 200")
        val resp = GroupMembersResp.parseFrom(response.result)
        assertEquals(3, resp.membersCount, "应有 3 个成员")
    }

    @Test
    fun `group members - 非成员返回NOT_MEMBER`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 3001L)
        } returns null  // 非成员

        val handler = GroupMembersHandler(conversationMemberRepository, userRepository)

        // 使用 outsiderSession（非成员）
        val authInterceptor = object : AuthInterceptor(
            sessionRegistry, skipMethods = setOf("system/ping")
        ) {
            override fun extractToken(request: Request): String? = outsiderSession.token
        }
        coEvery { sessionRegistry.validate(outsiderSession.token) } returns outsiderSession
        val registry = HandlerRegistry()
        registry.register(entry(handler, GroupMembersReq::class, GroupMembersResp::class))
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        val req = GroupMembersReq.newBuilder().setConversationId(testConvId).build()
        val response = withContext(SessionKey(outsiderSession)) {
            dispatcher.dispatch(request("conversation/group_members", req.toByteString()))
        }

        assertEquals(BizCode.NOT_MEMBER.code, response.code, "非成员应返回 NOT_MEMBER")
    }

    // ---------- conversation/edit_group_info ----------

    @Test
    fun `edit group - 群主修改名称返回200`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns ownerMember()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val handler = EditGroupHandler(conversationRepository, conversationMemberRepository, pushService)
        val dispatcher = buildDispatcher(entry(handler, EditGroupReq::class, Response::class))

        val req = EditGroupReq.newBuilder()
            .setConversationId(testConvId)
            .setName("新群名称")
            .build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/edit_group_info", req.toByteString()))
        }

        assertEquals(200, response.code, "群主修改名称应返回 200")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `edit group - 非群主返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns regularMember(2001L)  // 普通成员

        val handler = EditGroupHandler(conversationRepository, conversationMemberRepository, pushService)

        // 使用 memberSession
        val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = memberSession.token
        }
        coEvery { sessionRegistry.validate(memberSession.token) } returns memberSession
        val registry = HandlerRegistry()
        registry.register(entry(handler, EditGroupReq::class, Response::class))
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        val req = EditGroupReq.newBuilder().setConversationId(testConvId).setName("新名称").build()
        val response = withContext(SessionKey(memberSession)) {
            dispatcher.dispatch(request("conversation/edit_group_info", req.toByteString()))
        }

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "非群主应返回 GROUP_PERM_DENIED")
    }

    // ---------- conversation/invite_member ----------

    @Test
    fun `invite member - 正常邀请返回200`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns ownerMember()
        // 被邀请者 4001L 不在群中
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserIds(testConvId, listOf(4001L))
        } returns emptyList()
        coEvery { conversationMemberRepository.countActiveByConversationId(testConvId) } returns 3
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val handler = InviteMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, InviteMemberReq::class, Response::class))

        val req = InviteMemberReq.newBuilder()
            .setConversationId(testConvId)
            .addAllUids(listOf(4001L))
            .build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/invite_member", req.toByteString()))
        }

        assertEquals(200, response.code, "邀请成员应返回 200")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `invite member - 非成员无法邀请`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 3001L)
        } returns null  // outsider

        val handler = InviteMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = outsiderSession.token
        }
        coEvery { sessionRegistry.validate(outsiderSession.token) } returns outsiderSession
        val registry = HandlerRegistry()
        registry.register(entry(handler, InviteMemberReq::class, Response::class))
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        val req = InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build()
        val response = withContext(SessionKey(outsiderSession)) {
            dispatcher.dispatch(request("conversation/invite_member", req.toByteString()))
        }

        assertEquals(BizCode.NOT_MEMBER.code, response.code, "非成员邀请应返回 NOT_MEMBER")
    }

    // ---------- conversation/leave_group ----------

    @Test
    fun `leave group - 普通成员退群返回200`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns regularMember(2001L)
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val handler = LeaveGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = memberSession.token
        }
        coEvery { sessionRegistry.validate(memberSession.token) } returns memberSession
        val registry = HandlerRegistry()
        registry.register(entry(handler, LeaveGroupReq::class, Response::class))
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        val req = LeaveGroupReq.newBuilder().setConversationId(testConvId).build()
        val response = withContext(SessionKey(memberSession)) {
            dispatcher.dispatch(request("conversation/leave_group", req.toByteString()))
        }

        assertEquals(200, response.code, "成员退群应返回 200")
    }

    @Test
    fun `leave group - 群主退群触发解散`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns ownerMember()
        coEvery {
            conversationMemberRepository.softDeleteAllByConversationId(testConvId)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val handler = LeaveGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, LeaveGroupReq::class, Response::class))

        val req = LeaveGroupReq.newBuilder().setConversationId(testConvId).build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/leave_group", req.toByteString()))
        }

        assertEquals(200, response.code, "群主退群应返回 200")
        // 验证推送 GROUP_DISSOLVED
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    // ---------- conversation/kick_member ----------

    @Test
    fun `kick member - 群主踢人返回200`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns ownerMember()
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns regularMember(2001L)
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val handler = KickMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, KickMemberReq::class, Response::class))

        val req = KickMemberReq.newBuilder()
            .setConversationId(testConvId)
            .setUid(2001L)
            .build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/kick_member", req.toByteString()))
        }

        assertEquals(200, response.code, "群主踢人应返回 200")
        // 验证双推送：pushEventToUser(MEMBER_KICKED) + pushConversationEvent(MEMBER_LEFT)
        coVerify(exactly = 1) { pushService.pushEventToUser(any(), any(), any()) }
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `kick member - 非群主踢人返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns regularMember(2001L)  // 普通成员尝试踢人

        val handler = KickMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = memberSession.token
        }
        coEvery { sessionRegistry.validate(memberSession.token) } returns memberSession
        val registry = HandlerRegistry()
        registry.register(entry(handler, KickMemberReq::class, Response::class))
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        val req = KickMemberReq.newBuilder().setConversationId(testConvId).setUid(3001L).build()
        val response = withContext(SessionKey(memberSession)) {
            dispatcher.dispatch(request("conversation/kick_member", req.toByteString()))
        }

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "非群主踢人应返回 GROUP_PERM_DENIED")
    }

    @Test
    fun `kick member - 踢群主返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation()
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns ownerMember()
        // 目标成员也是群主（反踢群主）
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1002L)
        } returns ConversationMemberEntity(testConvId, 1002L, "owner").apply { id = 5L }

        val handler = KickMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )
        val dispatcher = buildDispatcher(entry(handler, KickMemberReq::class, Response::class))

        val req = KickMemberReq.newBuilder().setConversationId(testConvId).setUid(1002L).build()
        val response = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/kick_member", req.toByteString()))
        }

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "踢群主应返回 GROUP_PERM_DENIED")
    }

    // ===================================================================
    // 综合冒烟：完整流程测试
    // ===================================================================

    @Test
    fun `完整流程 - 创建群聊→查看成员→修改名称→邀请成员→踢人→退群`() = runTest {
        // ---- 创建群聊 ----
        val conv = testConversation().apply { name = "综合测试群" }
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val createHandler = CreateGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        // ---- 查看成员 ----
        val owner = ownerMember()
        val member1 = regularMember(2001L)
        val member2 = regularMember(3001L)
        coEvery { conversationMemberRepository.findByConversationId(testConvId) } returns listOf(owner, member1, member2)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        val user1 = testUser(1001L, "owner", "群主")
        val user2 = testUser(2001L, "m1", "成员1")
        val user3 = testUser(3001L, "m2", "成员2")
        every { userRepository.findAllById(any<List<Long>>()) } returns listOf(user1, user2, user3)

        val membersHandler = GroupMembersHandler(conversationMemberRepository, userRepository)

        // ---- 修改群名称 ----
        every { conversationRepository.findById(testConvId) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        val editHandler = EditGroupHandler(conversationRepository, conversationMemberRepository, pushService)

        // ---- 邀请成员 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserIds(testConvId, listOf(4001L))
        } returns emptyList()
        coEvery { conversationMemberRepository.countActiveByConversationId(testConvId) } returns 3
        val inviteHandler = InviteMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        // ---- 踢人 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns regularMember(2001L) andThen null  // 第一次返回成员，踢后返回 null
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs
        val kickHandler = KickMemberHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        // ---- 退群 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        coEvery {
            conversationMemberRepository.softDeleteAllByConversationId(testConvId)
        } just runs
        val leaveHandler = LeaveGroupHandler(
            conversationRepository, conversationMemberRepository,
            lockManager, transactionTemplate, pushService
        )

        // 注册所有 Handler
        val registry = HandlerRegistry()
        registry.register(entry(createHandler, CreateGroupReq::class, CreateGroupResp::class))
        registry.register(entry(membersHandler, GroupMembersReq::class, GroupMembersResp::class))
        registry.register(entry(editHandler, EditGroupReq::class, Response::class))
        registry.register(entry(inviteHandler, InviteMemberReq::class, Response::class))
        registry.register(entry(kickHandler, KickMemberReq::class, Response::class))
        registry.register(entry(leaveHandler, LeaveGroupReq::class, Response::class))

        val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")) {
            override fun extractToken(request: Request): String? = ownerSession.token
        }
        coEvery { sessionRegistry.validate(ownerSession.token) } returns ownerSession
        val dispatcher = Dispatcher(registry, listOf(authInterceptor, LogInterceptor(), RateLimitInterceptor(), ExceptionInterceptor()))

        // 步骤 1: 创建群聊
        val createReq = CreateGroupReq.newBuilder().setName("综合测试群").addAllMemberUids(listOf(2001L, 3001L)).build()
        val createResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/create_group", createReq.toByteString()))
        }
        assertEquals(200, createResp.code, "步骤1: 创建群聊应返回 200")

        // 步骤 2: 查看成员列表
        val membersReq = GroupMembersReq.newBuilder().setConversationId(testConvId).build()
        val membersResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/group_members", membersReq.toByteString()))
        }
        assertEquals(200, membersResp.code, "步骤2: 查看成员应返回 200")
        val parsed = GroupMembersResp.parseFrom(membersResp.result)
        assertEquals(3, parsed.membersCount, "步骤2: 应有 3 个成员")

        // 步骤 3: 修改群名称
        val editReq = EditGroupReq.newBuilder().setConversationId(testConvId).setName("改名后的群").build()
        val editResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/edit_group_info", editReq.toByteString()))
        }
        assertEquals(200, editResp.code, "步骤3: 修改名称应返回 200")

        // 步骤 4: 邀请新成员
        val inviteReq = InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build()
        val inviteResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/invite_member", inviteReq.toByteString()))
        }
        assertEquals(200, inviteResp.code, "步骤4: 邀请成员应返回 200")

        // 步骤 5: 踢出成员
        val kickReq = KickMemberReq.newBuilder().setConversationId(testConvId).setUid(2001L).build()
        val kickResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/kick_member", kickReq.toByteString()))
        }
        assertEquals(200, kickResp.code, "步骤5: 踢人应返回 200")

        // 步骤 6: 群主退群（解散）
        val leaveReq = LeaveGroupReq.newBuilder().setConversationId(testConvId).build()
        val leaveResp = withContext(SessionKey(ownerSession)) {
            dispatcher.dispatch(request("conversation/leave_group", leaveReq.toByteString()))
        }
        assertEquals(200, leaveResp.code, "步骤6: 群主退群应返回 200")
    }
}
