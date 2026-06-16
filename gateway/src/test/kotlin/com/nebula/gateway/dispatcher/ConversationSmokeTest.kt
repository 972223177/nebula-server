package com.nebula.gateway.dispatcher

import com.nebula.chat.Response
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.CreateGroupResp
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.GroupMembersResp
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.chat.group.GroupMember
import com.nebula.common.BizCode
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.buildTestDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.service.conversation.ConversationMemberInfo
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.CreateGroupResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 7 Conversation 集成冒烟测试 — 精简版。
 *
 * 只保留完整的端到端流程测试（创建 → 查看成员 → 修改名称 → 邀请 → 踢人 → 退群解散），
 * 单个 Handler 冒烟测试已移至对应 HandlerTest 中覆盖。
 */
class ConversationSmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var conversationService: ConversationService
    private lateinit var pushService: PushService
    private lateinit var sessionRegistry: SessionRegistry

    /** 测试用户 Session（userId=1001，群主） */
    private val ownerSession = Session(1001L, "token-owner", "MOBILE", "dev-1", "conn-1")
    /** 测试群 ID */
    private val testConvId = "550e8400-e29b-41d4-a716-446655440001"

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        pushService = mockk(relaxed = true)
        sessionRegistry = mockk()
    }

    // ===================================================================
    // 综合冒烟：完整流程测试
    // ===================================================================

    @Test
    fun fullFlowShouldCreateGroupThroughAllOperations() = runTest {
        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        // 预创建所有 Handler
        val createHandler = CreateGroupHandler(
            conversationService, lockManager, transactionTemplate, pushService
        )
        val membersHandler = GroupMembersHandler(conversationService)
        val editHandler = EditGroupHandler(conversationService, pushService)
        val inviteHandler = InviteMemberHandler(
            conversationService, lockManager, transactionTemplate, pushService
        )
        val kickHandler = KickMemberHandler(
            conversationService, lockManager, transactionTemplate, pushService
        )
        val leaveHandler = LeaveGroupHandler(
            conversationService, lockManager, transactionTemplate, pushService
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
        coEvery { conversationService.createGroup(any(), any()) } returns CreateGroupResult(
            convId = testConvId,
            name = "综合测试群",
            ownerUid = 1001L,
            memberUids = listOf(2001L, 3001L)
        )

        // ---- 查看成员 ----
        coEvery { conversationService.getGroupMembers(any(), any()) } returns GroupMembersResp.newBuilder()
            .addAllMembers(listOf(
                GroupMember.newBuilder().setUid(1001L).setRole("owner").build(),
                GroupMember.newBuilder().setUid(2001L).setRole("member").build(),
                GroupMember.newBuilder().setUid(3001L).setRole("member").build()
            ))
            .build()

        // ---- 修改群名称 ----
        coEvery { conversationService.editGroupInfo(any(), any()) } returns Unit

        // ---- 邀请成员 ----
        coEvery { conversationService.inviteMember(any(), any()) } returns listOf(4001L)

        // ---- 踢人 ----
        coEvery { conversationService.kickMember(any(), any()) } returns 2001L

        // ---- 退群 ----
        coEvery { conversationService.getMemberRole(any(), any()) } returns ConversationMemberInfo(userId = 1001L, role = "owner")
        coEvery { conversationService.dissolveGroup(any()) } returns Unit
        coEvery { conversationService.leaveGroup(any(), any()) } returns Unit

        // 步骤 1: 创建群聊
        val createResp = dispatcher.dispatchAs("conversation/create_group",
            CreateGroupReq.newBuilder().setName("综合测试群").addAllMemberUids(listOf(2001L, 3001L)).build())
        assertEquals(BizCode.OK.code, createResp.code, "步骤1: 创建群聊应返回 200")

        // 步骤 2: 查看成员列表
        val membersResp = dispatcher.dispatchAs("conversation/group_members",
            GroupMembersReq.newBuilder().setConversationId(testConvId).build())
        assertEquals(BizCode.OK.code, membersResp.code, "步骤2: 查看成员应返回 200")
        assertEquals(3, GroupMembersResp.parseFrom(membersResp.result).membersCount, "步骤2: 应有 3 个成员")

        // 步骤 3: 修改群名称
        val editResp = dispatcher.dispatchAs("conversation/edit_group_info",
            EditGroupReq.newBuilder().setConversationId(testConvId).setName("改名后的群").build())
        assertEquals(BizCode.OK.code, editResp.code, "步骤3: 修改名称应返回 200")

        // 步骤 4: 邀请新成员
        val inviteResp = dispatcher.dispatchAs("conversation/invite_member",
            InviteMemberReq.newBuilder().setConversationId(testConvId).addAllUids(listOf(4001L)).build())
        assertEquals(BizCode.OK.code, inviteResp.code, "步骤4: 邀请成员应返回 200")

        // 步骤 5: 踢出成员
        val kickResp = dispatcher.dispatchAs("conversation/kick_member",
            KickMemberReq.newBuilder().setConversationId(testConvId).setUid(2001L).build())
        assertEquals(BizCode.OK.code, kickResp.code, "步骤5: 踢人应返回 200")

        // 步骤 6: 群主退群（解散）
        val leaveResp = dispatcher.dispatchAs("conversation/leave_group",
            LeaveGroupReq.newBuilder().setConversationId(testConvId).build())
        assertEquals(BizCode.OK.code, leaveResp.code, "步骤6: 群主退群应返回 200")
    }
}
