package com.nebula.service.conversation

import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.UserRepository
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ConversationService 的 MockK 单元测试。
 *
 * 覆盖 7 个业务方法的正常流程和异常分支：
 * - createGroup、listConversations、inviteMember、leaveGroup、kickMember、editGroupInfo、getGroupMembers
 */
class ConversationServiceTest {

    // ── 被 mock 的依赖 ──
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var service: ConversationService

    // ── 测试用实体 ──
    private val convId = "conv1"
    private val ownerUid = 1001L
    private val memberUid1 = 2001L
    private val memberUid2 = 2002L
    private val operatorUid = ownerUid
    private val now = LocalDateTime.of(2025, 6, 1, 12, 0, 0)

    /** 群聊会话实体 */
    private val groupConversation: ConversationEntity by lazy {
        ConversationEntity(type = 2, name = "测试群", memberCount = 3).apply {
            id = convId
            createdAt = now
            updatedAt = now
        }
    }

    /** 群主成员记录 */
    private val ownerMemberEntity: ConversationMemberEntity by lazy {
        ConversationMemberEntity(conversationId = convId, userId = ownerUid, role = "owner", deleted = 0).apply {
            joinedAt = now
        }
    }

    /** 普通成员记录 */
    private val memberEntity1: ConversationMemberEntity by lazy {
        ConversationMemberEntity(conversationId = convId, userId = memberUid1, role = "member", deleted = 0).apply {
            joinedAt = now
        }
    }

    /** 另一个普通成员记录 */
    private val memberEntity2: ConversationMemberEntity by lazy {
        ConversationMemberEntity(conversationId = convId, userId = memberUid2, role = "member", deleted = 0).apply {
            joinedAt = now
        }
    }

    /** 用户实体 */
    private val userEntity: UserEntity by lazy {
        UserEntity(username = "test_user", passwordHash = "", nickname = "测试用户").apply {
            id = memberUid1
        }
    }

    @BeforeEach
    fun setup() {
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        service = ConversationService(conversationRepository, conversationMemberRepository, userRepository)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // =========================================================================
    // createGroup
    // =========================================================================

    /** 群名称为空时抛出 INVALID_PARAM */
    @Test
    fun createGroupShouldThrowInvalidParamWhenNameIsBlank() = runTest {
        val req = CreateGroupReq.newBuilder()
            .setName("")
            .addAllMemberUids(listOf(memberUid1))
            .build()

        val ex = assertThrows<ConversationException> {
            service.createGroup(req, ownerUid)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /** 群名称超过 128 字符时抛出 INVALID_PARAM */
    @Test
    fun createGroupShouldThrowInvalidParamWhenNameExceeds128Chars() = runTest {
        val longName = "群".repeat(129)
        val req = CreateGroupReq.newBuilder()
            .setName(longName)
            .addAllMemberUids(listOf(memberUid1))
            .build()

        val ex = assertThrows<ConversationException> {
            service.createGroup(req, ownerUid)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /** 群主在初始成员列表中时抛出 INVALID_PARAM */
    @Test
    fun createGroupShouldThrowInvalidParamWhenOwnerUidInMemberUidsList() = runTest {
        val req = CreateGroupReq.newBuilder()
            .setName("测试群")
            .addAllMemberUids(listOf(ownerUid, memberUid1))
            .build()

        val ex = assertThrows<ConversationException> {
            service.createGroup(req, ownerUid)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /** 成员总数超过 200 时抛出 GROUP_FULL */
    @Test
    fun createGroupShouldThrowGroupFullWhenTotalMemberCountExceeds200() = runTest {
        val memberIds = (1..200).map { it.toLong() }
        val req = CreateGroupReq.newBuilder()
            .setName("测试群")
            .addAllMemberUids(memberIds)
            .build()

        val ex = assertThrows<ConversationException> {
            service.createGroup(req, ownerUid)
        }
        assertEquals(BizCode.GROUP_FULL, ex.bizCode)
    }

    /** 正常创建群聊：创建会话、群主记录、成员记录，返回 CreateGroupResult */
    @Test
    fun createGroupShouldCreateConversationAndMembersSuccessfully() = runTest {
        val memberIds = listOf(memberUid1, memberUid2)
        val req = CreateGroupReq.newBuilder()
            .setName("测试群")
            .addAllMemberUids(memberIds)
            .build()

        // 拦截 save 调用，返回传入的实体
        coEvery { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val result = service.createGroup(req, ownerUid)

        // 验证返回结果
        assertNotNull(result.convId)
        assertEquals("测试群", result.name)
        assertEquals(ownerUid, result.ownerUid)
        assertEquals(memberIds, result.memberUids)

        // 验证保存了会话实体
        coVerify(exactly = 1) { conversationRepository.save(match<ConversationEntity> {
            it.type == 2 && it.name == "测试群" && it.memberCount == 3
        }) }

        // 验证保存了群主记录
        coVerify(exactly = 1) { conversationMemberRepository.save(match<ConversationMemberEntity> {
            it.role == "owner" && it.userId == ownerUid
        }) }

        // 验证保存了每位成员记录
        coVerify(exactly = 1) { conversationMemberRepository.save(match {
            it.userId == memberUid1 && it.role == "member"
        }) }
        coVerify(exactly = 1) { conversationMemberRepository.save(match {
            it.userId == memberUid2 && it.role == "member"
        }) }
    }

    // =========================================================================
    // listConversations
    // =========================================================================

    /** 会话列表为空时返回空响应，hasMore = false */
    @Test
    fun listConversationsShouldReturnEmptyResponseWhenNoConversations() = runTest {
        coEvery {
            conversationRepository.findConversationsByUserId(any(), any(), any())
        } returns emptyList()

        val resp = service.listConversations(userId = ownerUid, cursor = 0L, limit = 20)

        assertEquals(0, resp.conversationsCount)
        assertEquals(false, resp.hasMore)
    }

    /** 正常查询会话列表：返回会话及成员映射 */
    @Test
    fun listConversationsShouldReturnConversationsWithMemberMapping() = runTest {
        val convList = listOf(groupConversation)

        coEvery {
            conversationRepository.findConversationsByUserId(any(), any(), any())
        } returns convList

        coEvery {
            conversationMemberRepository.findByConversationIdsAndUserId(listOf(convId), ownerUid)
        } returns listOf(ownerMemberEntity)

        val resp = service.listConversations(userId = ownerUid, cursor = 0L, limit = 20)

        assertEquals(1, resp.conversationsCount)
        assertEquals(false, resp.hasMore)
        val brief = resp.getConversations(0)
        assertEquals(convId, brief.conversationId)
        assertEquals("测试群", brief.name)
    }

    // =========================================================================
    // inviteMember
    // =========================================================================

    /** 会话不存在时抛出 CONV_NOT_FOUND */
    @Test
    fun inviteMemberShouldThrowConvNotFoundWhenConversationNotFound() = runTest {
        val req = InviteMemberReq.newBuilder()
            .setConversationId("non-existent")
            .addAllUids(listOf(memberUid1))
            .build()

        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val ex = assertThrows<ConversationException> {
            service.inviteMember(req, operatorUid)
        }
        assertEquals(BizCode.CONV_NOT_FOUND, ex.bizCode)
    }

    /** 操作者不是群主时抛出 GROUP_PERM_DENIED */
    @Test
    fun inviteMemberShouldThrowGroupPermDeniedWhenOperatorIsNotOwner() = runTest {
        val req = InviteMemberReq.newBuilder()
            .setConversationId(convId)
            .addAllUids(listOf(memberUid1))
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns memberEntity1

        val ex = assertThrows<ConversationException> {
            service.inviteMember(req, 9999L)
        }
        assertEquals(BizCode.GROUP_PERM_DENIED, ex.bizCode)
    }

    /** 跳过已在群中的活跃成员 */
    @Test
    fun inviteMemberShouldSkipExistingActiveMembers() = runTest {
        val req = InviteMemberReq.newBuilder()
            .setConversationId(convId)
            .addAllUids(listOf(memberUid1))
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        // memberUid1 已是活跃成员
        coEvery { conversationMemberRepository.findByConversationIdAndUserIds(convId, listOf(memberUid1)) } returns listOf(memberEntity1)
        coEvery { conversationRepository.incrementMemberCount(convId, 0) } returns 0

        val invited = service.inviteMember(req, operatorUid)

        // 应跳过 memberUid1，不加入 newMemberUids
        assertTrue(invited.isEmpty())
        coVerify(exactly = 1) { conversationRepository.incrementMemberCount(convId, 0) }
    }

    /** 恢复已软删除的成员 */
    @Test
    fun inviteMemberShouldRestoreSoftDeletedMembers() = runTest {
        val req = InviteMemberReq.newBuilder()
            .setConversationId(convId)
            .addAllUids(listOf(memberUid1))
            .build()

        val softDeletedMember = ConversationMemberEntity(
            conversationId = convId, userId = memberUid1, role = "member", deleted = 1
        ).apply { joinedAt = now }

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        // 成员已存在但 deleted=1
        coEvery { conversationMemberRepository.findByConversationIdAndUserIds(convId, listOf(memberUid1)) } returns listOf(softDeletedMember)
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        coEvery { conversationRepository.incrementMemberCount(convId, 1) } returns 1

        val invited = service.inviteMember(req, operatorUid)

        assertEquals(listOf(memberUid1), invited)
        // 验证恢复操作：deleted 被置 0
        coVerify { conversationMemberRepository.save(match<ConversationMemberEntity> { it.deleted == 0 }) }
    }

    /** 正常邀请新成员 */
    @Test
    fun inviteMemberShouldCreateNewMembersForNewInvitations() = runTest {
        val newUid = 3001L
        val req = InviteMemberReq.newBuilder()
            .setConversationId(convId)
            .addAllUids(listOf(newUid))
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        // 新成员不在群中
        coEvery { conversationMemberRepository.findByConversationIdAndUserIds(convId, listOf(newUid)) } returns emptyList()
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        coEvery { conversationRepository.incrementMemberCount(convId, 1) } returns 1

        val invited = service.inviteMember(req, operatorUid)

        assertEquals(listOf(newUid), invited)
        coVerify { conversationMemberRepository.save(match<ConversationMemberEntity> {
            it.userId == newUid && it.role == "member"
        }) }
    }

    /** 邀请成功后更新成员计数 */
    @Test
    fun inviteMemberShouldUpdateMemberCount() = runTest {
        val newUid = 3001L
        val req = InviteMemberReq.newBuilder()
            .setConversationId(convId)
            .addAllUids(listOf(newUid))
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        coEvery { conversationMemberRepository.findByConversationIdAndUserIds(convId, listOf(newUid)) } returns emptyList()
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        coEvery { conversationRepository.incrementMemberCount(convId, 1) } returns 1

        service.inviteMember(req, operatorUid)

        coVerify { conversationRepository.incrementMemberCount(convId, 1) }
    }

    // =========================================================================
    // leaveGroup
    // =========================================================================

    /** 会话不存在时抛出 CONV_NOT_FOUND */
    @Test
    fun leaveGroupShouldThrowConvNotFoundWhenConversationNotFound() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId("non-existent").build()

        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val ex = assertThrows<ConversationException> {
            service.leaveGroup(req, ownerUid)
        }
        assertEquals(BizCode.CONV_NOT_FOUND, ex.bizCode)
    }

    /** 用户不是成员时抛出 NOT_MEMBER */
    @Test
    fun leaveGroupShouldThrowNotMemberWhenUserNotAMember() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns null

        val ex = assertThrows<ConversationException> {
            service.leaveGroup(req, 9999L)
        }
        assertEquals(BizCode.NOT_MEMBER, ex.bizCode)
    }

    /** 群主不能退群时抛出 GROUP_PERM_DENIED */
    @Test
    fun leaveGroupShouldThrowGroupPermDeniedWhenOwnerTriesToLeave() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, ownerUid) } returns ownerMemberEntity

        val ex = assertThrows<ConversationException> {
            service.leaveGroup(req, ownerUid)
        }
        assertEquals(BizCode.GROUP_PERM_DENIED, ex.bizCode)
    }

    /** 正常退群：软删除成员并更新计数 */
    @Test
    fun leaveGroupShouldSoftDeleteMemberAndUpdateMemberCount() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        coEvery { conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1) } just Runs
        coEvery { conversationRepository.incrementMemberCount(convId, -1) } returns 1

        service.leaveGroup(req, memberUid1)

        coVerify(exactly = 1) {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1)
        }
        coVerify { conversationRepository.incrementMemberCount(convId, -1) }
    }

    // =========================================================================
    // kickMember
    // =========================================================================

    /** 会话不存在时抛出 CONV_NOT_FOUND */
    @Test
    fun kickMemberShouldThrowConvNotFoundWhenConversationNotFound() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId("non-existent")
            .setUid(memberUid1)
            .build()

        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val ex = assertThrows<ConversationException> {
            service.kickMember(req, operatorUid)
        }
        assertEquals(BizCode.CONV_NOT_FOUND, ex.bizCode)
    }

    /** 操作者不是群主时抛出 GROUP_PERM_DENIED */
    @Test
    fun kickMemberShouldThrowGroupPermDeniedWhenOperatorIsNotOwner() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId(convId)
            .setUid(memberUid1)
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns memberEntity1

        val ex = assertThrows<ConversationException> {
            service.kickMember(req, 9999L)
        }
        assertEquals(BizCode.GROUP_PERM_DENIED, ex.bizCode)
    }

    /** 目标不是成员时抛出 NOT_MEMBER */
    @Test
    fun kickMemberShouldThrowNotMemberWhenTargetNotAMember() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId(convId)
            .setUid(9999L)
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns null

        val ex = assertThrows<ConversationException> {
            service.kickMember(req, operatorUid)
        }
        assertEquals(BizCode.NOT_MEMBER, ex.bizCode)
    }

    /** 不能踢群主时抛出 GROUP_PERM_DENIED */
    @Test
    fun kickMemberShouldThrowGroupPermDeniedWhenTargetingOwner() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId(convId)
            .setUid(ownerUid)
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, ownerUid) } returns ownerMemberEntity

        val ex = assertThrows<ConversationException> {
            service.kickMember(req, operatorUid)
        }
        assertEquals(BizCode.GROUP_PERM_DENIED, ex.bizCode)
    }

    /** 正常踢人：软删除目标并更新计数 */
    @Test
    fun kickMemberShouldSoftDeleteTargetAndUpdateMemberCount() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId(convId)
            .setUid(memberUid1)
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        coEvery { conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1) } just Runs
        coEvery { conversationRepository.incrementMemberCount(convId, -1) } returns 1

        val kickedUid = service.kickMember(req, operatorUid)

        assertEquals(memberUid1, kickedUid)
        coVerify(exactly = 1) {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1)
        }
        coVerify { conversationRepository.incrementMemberCount(convId, -1) }
    }

    // =========================================================================
    // editGroupInfo
    // =========================================================================

    /** 会话不存在时抛出 CONV_NOT_FOUND */
    @Test
    fun editGroupInfoShouldThrowConvNotFoundWhenConversationNotFound() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId("non-existent")
            .setName("新名称")
            .build()

        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val ex = assertThrows<ConversationException> {
            service.editGroupInfo(req, operatorUid)
        }
        assertEquals(BizCode.CONV_NOT_FOUND, ex.bizCode)
    }

    /** 操作者不是群主时抛出 GROUP_PERM_DENIED */
    @Test
    fun editGroupInfoShouldThrowGroupPermDeniedWhenNotOwner() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId(convId)
            .setName("新名称")
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1

        val ex = assertThrows<ConversationException> {
            service.editGroupInfo(req, memberUid1)
        }
        assertEquals(BizCode.GROUP_PERM_DENIED, ex.bizCode)
    }

    /** 名称为空时抛出 INVALID_PARAM */
    @Test
    fun editGroupInfoShouldThrowInvalidParamWhenNameIsBlank() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId(convId)
            .setName("")
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity

        val ex = assertThrows<ConversationException> {
            service.editGroupInfo(req, operatorUid)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /** 正常编辑：更新群名和头像 */
    @Test
    fun editGroupInfoShouldUpdateNameAndAvatar() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId(convId)
            .setName("新群名")
            .setAvatarUrl("https://example.com/avatar.png")
            .build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        coEvery { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        service.editGroupInfo(req, operatorUid)

        coVerify {
            conversationRepository.save(match<ConversationEntity> {
                it.name == "新群名" && it.avatar == "https://example.com/avatar.png"
            })
        }
    }

    // =========================================================================
    // getGroupMembers
    // =========================================================================

    /** 用户不是成员时抛出 NOT_MEMBER */
    @Test
    fun getGroupMembersShouldThrowNotMemberWhenNotAMember() = runTest {
        val req = GroupMembersReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns null

        val ex = assertThrows<ConversationException> {
            service.getGroupMembers(req, 9999L)
        }
        assertEquals(BizCode.NOT_MEMBER, ex.bizCode)
    }

    /** 正常查询：返回成员列表并合并用户信息 */
    @Test
    fun getGroupMembersShouldReturnMembersWithUserInfo() = runTest {
        val req = GroupMembersReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        coEvery { conversationMemberRepository.findByConversationId(convId) } returns listOf(
            ownerMemberEntity, memberEntity1
        )
        coEvery { userRepository.findAllById(listOf(ownerUid, memberUid1)) } returns listOf(
            UserEntity(username = "owner", passwordHash = "", nickname = "群主", avatar = "").apply { id = ownerUid },
            UserEntity(username = "member1", passwordHash = "", nickname = "成员1", avatar = "https://example.com/avatar.png").apply { id = memberUid1 }
        )

        val resp = service.getGroupMembers(req, memberUid1)

        assertEquals(2, resp.membersCount)

        // 验证群主
        val ownerMember = resp.getMembers(0)
        assertEquals(ownerUid, ownerMember.uid)
        assertEquals("owner", ownerMember.username)
        assertEquals("群主", ownerMember.displayName)
        assertEquals("owner", ownerMember.role)

        // 验证成员
        val member = resp.getMembers(1)
        assertEquals(memberUid1, member.uid)
        assertEquals("member1", member.username)
        assertEquals("成员1", member.displayName)
        assertEquals("https://example.com/avatar.png", member.avatarUrl)
        assertEquals("member", member.role)
    }
}
