package com.nebula.gateway.dispatcher

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
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.buildTestDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.gateway.testutil.testConversation
import com.nebula.gateway.testutil.testMember
import com.nebula.gateway.testutil.testUser
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.service.conversation.ConversationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7 Conversation 集成冒烟测试。
 *
 * 通过 Dispatcher 测试完整的 request→dispatch→response 链路，
 * 覆盖 Phase 7 全部 7 个 Conversation Handler 的端到端行为。
 *
 * 使用 [TestHelper] 提供的工具函数简化测试构建：
 * - [handlerEntry] 替代手写 ProtoCodec 逻辑
 * - [dispatchAs] 替代 `withContext + SessionKey + dispatch + requestEnvelope` 模式
 * - [buildTestDispatcher] 替代手写 Interceptor Pipeline
 * - [mockLockManager] / [mockTransactionTemplate] 替代手写 Mock
 */
class ConversationSmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var conversationService: ConversationService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
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

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        pushService = mockk(relaxed = true)
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
     * @param session 测试 Session，默认 ownerSession
     * @return 配置好的 Dispatcher
     */
    private fun <Req : Any, Resp : Any> singleHandlerDispatcher(
        handler: Handler<Req, Resp>,
        reqClass: kotlin.reflect.KClass<Req>,
        respClass: kotlin.reflect.KClass<Resp>,
        session: Session = ownerSession
    ) = buildTestDispatcher(
        HandlerRegistry().apply { register(handlerEntry(handler, reqClass, respClass)) },
        session = session, sessionRegistry = sessionRegistry
    )

    // ===================================================================
    // 冒烟测试用例
    // ===================================================================

    // ---------- conversation/list ----------

    @Test
    fun `conversation list - 空列表返回200`() = runTest {
        coEvery {
            conversationRepository.findConversationsByUserId(1001L, null, any<PageRequest>())
        } returns emptyList()

        val dispatcher = singleHandlerDispatcher(
            ListConversationsHandler(conversationService),
            ConvListReq::class, ConvListResp::class
        )

        val response = dispatcher.dispatchAs("conversation/list",
            ConvListReq.newBuilder().setLimit(20).build())

        assertEquals(200, response.code, "空会话列表应返回 200")
        val resp = ConvListResp.parseFrom(response.result)
        assertEquals(0, resp.conversationsCount, "空列表应有 0 个会话")
        assertEquals(false, resp.hasMore, "空列表 hasMore 应为 false")
    }

    @Test
    fun `conversation list - 有会话返回列表`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        coEvery {
            conversationRepository.findConversationsByUserId(1001L, null, any<PageRequest>())
        } returns listOf(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdsAndUserId(any(), 1001L)
        } returns listOf(
            testMember(testConvId, 1001L, "owner").apply { id = 1L }
        )

        val dispatcher = singleHandlerDispatcher(
            ListConversationsHandler(conversationService),
            ConvListReq::class, ConvListResp::class
        )

        val response = dispatcher.dispatchAs("conversation/list",
            ConvListReq.newBuilder().setLimit(20).build())

        assertEquals(200, response.code, "会话列表应返回 200")
        assertEquals(1, ConvListResp.parseFrom(response.result).conversationsCount, "应有 1 个会话")
    }

    // ---------- conversation/create_group ----------

    @Test
    fun `create group - 正常创建返回conversationId`() = runTest {
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            CreateGroupHandler(conversationService, mockLockManager(), pushService),
            CreateGroupReq::class, CreateGroupResp::class
        )

        val response = dispatcher.dispatchAs("conversation/create_group",
            CreateGroupReq.newBuilder().setName("新测试群").addAllMemberUids(listOf(2001L, 3001L)).build())

        assertEquals(200, response.code, "创建群聊应返回 200")
        val resp = CreateGroupResp.parseFrom(response.result)
        assertTrue(resp.conversationId.isNotEmpty(), "conversationId 不应为空")
        assertEquals("新测试群", resp.name, "群名称应匹配")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `create group - name为空返回非200`() = runTest {
        val dispatcher = singleHandlerDispatcher(
            CreateGroupHandler(conversationService, mockLockManager(), pushService),
            CreateGroupReq::class, CreateGroupResp::class
        )

        val response = dispatcher.dispatchAs("conversation/create_group",
            CreateGroupReq.newBuilder().setName("").build())

        assertTrue(response.code != 200, "空名称应返回非 200 错误码")
    }

    @Test
    fun `create group - 成员数超上限返回GROUP_FULL`() = runTest {
        val dispatcher = singleHandlerDispatcher(
            CreateGroupHandler(conversationService, mockLockManager(), pushService),
            CreateGroupReq::class, CreateGroupResp::class
        )

        val tooManyMembers = (2001L..2201L).toList()
        val response = dispatcher.dispatchAs("conversation/create_group",
            CreateGroupReq.newBuilder().setName("超大群").addAllMemberUids(tooManyMembers).build())

        assertEquals(BizCode.GROUP_FULL.code, response.code, "超上限应返回 GROUP_FULL")
    }

    // ---------- conversation/group_members ----------

    @Test
    fun `group members - 成员可查看成员列表`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)

        val owner = testMember(testConvId, 1001L, "owner").apply { id = 1L }
        val member1 = testMember(testConvId, 2001L)
        val member2 = testMember(testConvId, 3001L)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        coEvery { conversationMemberRepository.findByConversationId(testConvId) } returns listOf(owner, member1, member2)
        every { userRepository.findAllById(listOf(1001L, 2001L, 3001L)) } returns listOf(
            testUser(1001L, "owner", "群主"),
            testUser(2001L, "member1", "成员1"),
            testUser(3001L, "member2", "成员2")
        )

        val dispatcher = singleHandlerDispatcher(
            GroupMembersHandler(conversationService),
            GroupMembersReq::class, GroupMembersResp::class
        )

        val response = dispatcher.dispatchAs("conversation/group_members",
            GroupMembersReq.newBuilder().setConversationId(testConvId).build())

        assertEquals(200, response.code, "成员查看列表应返回 200")
        assertEquals(3, GroupMembersResp.parseFrom(response.result).membersCount, "应有 3 个成员")
    }

    @Test
    fun `group members - 非成员返回NOT_MEMBER`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 3001L)
        } returns null  // 非成员

        val dispatcher = singleHandlerDispatcher(
            GroupMembersHandler(conversationService),
            GroupMembersReq::class, GroupMembersResp::class,
            session = outsiderSession
        )

        val response = dispatcher.dispatchAs("conversation/group_members",
            GroupMembersReq.newBuilder().setConversationId(testConvId).build())

        assertEquals(BizCode.NOT_MEMBER.code, response.code, "非成员应返回 NOT_MEMBER")
    }

    // ---------- conversation/edit_group_info ----------

    @Test
    fun `edit group - 群主修改名称返回200`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns testMember(testConvId, 1001L, "owner").apply { id = 1L }
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            EditGroupHandler(conversationService, pushService),
            EditGroupReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("conversation/edit_group_info",
            EditGroupReq.newBuilder().setConversationId(testConvId).setName("新群名称").build())

        assertEquals(200, response.code, "群主修改名称应返回 200")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `edit group - 非群主返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns testMember(testConvId, 2001L)  // 普通成员

        val dispatcher = singleHandlerDispatcher(
            EditGroupHandler(conversationService, pushService),
            EditGroupReq::class, Response::class,
            session = memberSession
        )

        val response = dispatcher.dispatchAs("conversation/edit_group_info",
            EditGroupReq.newBuilder().setConversationId(testConvId).setName("新名称").build())

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "非群主应返回 GROUP_PERM_DENIED")
    }

    // ---------- conversation/invite_member ----------

    @Test
    fun `invite member - 正常邀请返回200`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns testMember(testConvId, 1001L, "owner").apply { id = 1L }
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserIds(testConvId, listOf(4001L))
        } returns emptyList()
        coEvery { conversationMemberRepository.countActiveByConversationId(testConvId) } returns 3
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            InviteMemberHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            InviteMemberReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("conversation/invite_member",
            InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build())

        assertEquals(200, response.code, "邀请成员应返回 200")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `invite member - 非成员无法邀请`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 3001L)
        } returns null  // outsider

        val dispatcher = singleHandlerDispatcher(
            InviteMemberHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            InviteMemberReq::class, Response::class,
            session = outsiderSession
        )

        val response = dispatcher.dispatchAs("conversation/invite_member",
            InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build())

        assertEquals(BizCode.NOT_MEMBER.code, response.code, "非成员邀请应返回 NOT_MEMBER")
    }

    // ---------- conversation/leave_group ----------

    @Test
    fun `leave group - 普通成员退群返回200`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns testMember(testConvId, 2001L)
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            LeaveGroupHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            LeaveGroupReq::class, Response::class,
            session = memberSession
        )

        val response = dispatcher.dispatchAs("conversation/leave_group",
            LeaveGroupReq.newBuilder().setConversationId(testConvId).build())

        assertEquals(200, response.code, "成员退群应返回 200")
    }

    @Test
    fun `leave group - 群主退群触发解散`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns testMember(testConvId, 1001L, "owner").apply { id = 1L }
        coEvery {
            conversationMemberRepository.softDeleteAllByConversationId(testConvId)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            LeaveGroupHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            LeaveGroupReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("conversation/leave_group",
            LeaveGroupReq.newBuilder().setConversationId(testConvId).build())

        assertEquals(200, response.code, "群主退群应返回 200")
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    // ---------- conversation/kick_member ----------

    @Test
    fun `kick member - 群主踢人返回200`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns testMember(testConvId, 1001L, "owner").apply { id = 1L }
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns testMember(testConvId, 2001L)
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            KickMemberHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            KickMemberReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("conversation/kick_member",
            KickMemberReq.newBuilder().setConversationId(testConvId).setUid(2001L).build())

        assertEquals(200, response.code, "群主踢人应返回 200")
        coVerify(exactly = 1) { pushService.pushEventToUser(any(), any(), any()) }
        coVerify(exactly = 1) { pushService.pushConversationEvent(any(), any(), any(), any<Set<Long>>()) }
    }

    @Test
    fun `kick member - 非群主踢人返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns testMember(testConvId, 2001L)  // 普通成员尝试踢人

        val dispatcher = singleHandlerDispatcher(
            KickMemberHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            KickMemberReq::class, Response::class,
            session = memberSession
        )

        val response = dispatcher.dispatchAs("conversation/kick_member",
            KickMemberReq.newBuilder().setConversationId(testConvId).setUid(3001L).build())

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "非群主踢人应返回 GROUP_PERM_DENIED")
    }

    @Test
    fun `kick member - 踢群主返回GROUP_PERM_DENIED`() = runTest {
        val conv = testConversation(testConvId, testGroupName)
        every { conversationRepository.findById(any()) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns testMember(testConvId, 1001L, "owner").apply { id = 1L }
        // 目标成员也是群主（反踢群主）
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1002L)
        } returns ConversationMemberEntity(testConvId, 1002L, "owner").apply { id = 5L }

        val dispatcher = singleHandlerDispatcher(
            KickMemberHandler(conversationService,
                mockLockManager(), mockTransactionTemplate(), pushService, conversationMemberRepository),
            KickMemberReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("conversation/kick_member",
            KickMemberReq.newBuilder().setConversationId(testConvId).setUid(1002L).build())

        assertEquals(BizCode.GROUP_PERM_DENIED.code, response.code, "踢群主应返回 GROUP_PERM_DENIED")
    }

    // ===================================================================
    // 综合冒烟：完整流程测试
    // ===================================================================

    @Test
    fun `完整流程 - 创建群聊→查看成员→修改名称→邀请成员→踢人→退群`() = runTest {
        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        // 预创建所有 Handler
        val createHandler = CreateGroupHandler(
            conversationService, lockManager, pushService
        )
        val membersHandler = GroupMembersHandler(conversationService)
        val editHandler = EditGroupHandler(conversationService, pushService)
        val inviteHandler = InviteMemberHandler(
            conversationService, lockManager, transactionTemplate, pushService, conversationMemberRepository
        )
        val kickHandler = KickMemberHandler(
            conversationService, lockManager, transactionTemplate, pushService, conversationMemberRepository
        )
        val leaveHandler = LeaveGroupHandler(
            conversationService, lockManager, transactionTemplate, pushService, conversationMemberRepository
        )

        // 注册所有 Handler
        val registry = HandlerRegistry()
        registry.register(handlerEntry(createHandler, CreateGroupReq::class, CreateGroupResp::class))
        registry.register(handlerEntry(membersHandler, GroupMembersReq::class, GroupMembersResp::class))
        registry.register(handlerEntry(editHandler, EditGroupReq::class, Response::class))
        registry.register(handlerEntry(inviteHandler, InviteMemberReq::class, Response::class))
        registry.register(handlerEntry(kickHandler, KickMemberReq::class, Response::class))
        registry.register(handlerEntry(leaveHandler, LeaveGroupReq::class, Response::class))

        val dispatcher = buildTestDispatcher(registry, session = ownerSession, sessionRegistry = sessionRegistry)

        // ---- 创建群聊 ----
        val conv = testConversation(testConvId, "综合测试群")
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        // ---- 查看成员 ----
        val owner = testMember(testConvId, 1001L, "owner").apply { id = 1L }
        val member1 = testMember(testConvId, 2001L)
        val member2 = testMember(testConvId, 3001L)
        coEvery { conversationMemberRepository.findByConversationId(testConvId) } returns listOf(owner, member1, member2)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        every { userRepository.findAllById(any<List<Long>>()) } returns listOf(
            testUser(1001L, "owner", "群主"),
            testUser(2001L, "m1", "成员1"),
            testUser(3001L, "m2", "成员2")
        )

        // ---- 修改群名称 ----
        every { conversationRepository.findById(testConvId) } returns Optional.of(conv)
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner

        // ---- 邀请成员 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserIds(testConvId, listOf(4001L))
        } returns emptyList()
        coEvery { conversationMemberRepository.countActiveByConversationId(testConvId) } returns 3

        // ---- 踢人 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 2001L)
        } returns testMember(testConvId, 2001L) andThen null
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(testConvId, 2001L)
        } just runs

        // ---- 退群 ----
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(testConvId, 1001L)
        } returns owner
        coEvery {
            conversationMemberRepository.softDeleteAllByConversationId(testConvId)
        } just runs

        // 步骤 1: 创建群聊
        val createResp = dispatcher.dispatchAs("conversation/create_group",
            CreateGroupReq.newBuilder().setName("综合测试群").addAllMemberUids(listOf(2001L, 3001L)).build())
        assertEquals(200, createResp.code, "步骤1: 创建群聊应返回 200")

        // 步骤 2: 查看成员列表
        val membersResp = dispatcher.dispatchAs("conversation/group_members",
            GroupMembersReq.newBuilder().setConversationId(testConvId).build())
        assertEquals(200, membersResp.code, "步骤2: 查看成员应返回 200")
        assertEquals(3, GroupMembersResp.parseFrom(membersResp.result).membersCount, "步骤2: 应有 3 个成员")

        // 步骤 3: 修改群名称
        val editResp = dispatcher.dispatchAs("conversation/edit_group_info",
            EditGroupReq.newBuilder().setConversationId(testConvId).setName("改名后的群").build())
        assertEquals(200, editResp.code, "步骤3: 修改名称应返回 200")

        // 步骤 4: 邀请新成员
        val inviteResp = dispatcher.dispatchAs("conversation/invite_member",
            InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build())
        assertEquals(200, inviteResp.code, "步骤4: 邀请成员应返回 200")

        // 步骤 5: 踢出成员
        val kickResp = dispatcher.dispatchAs("conversation/kick_member",
            KickMemberReq.newBuilder().setConversationId(testConvId).setUid(2001L).build())
        assertEquals(200, kickResp.code, "步骤5: 踢人应返回 200")

        // 步骤 6: 群主退群（解散）
        val leaveResp = dispatcher.dispatchAs("conversation/leave_group",
            LeaveGroupReq.newBuilder().setConversationId(testConvId).build())
        assertEquals(200, leaveResp.code, "步骤6: 群主退群应返回 200")
    }
}
