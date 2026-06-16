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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // 恢复软删除成员不增加计数（不加入 newMemberUids），因此 delta=0
        coEvery { conversationRepository.incrementMemberCount(convId, 0) } returns 0

        val invited = service.inviteMember(req, operatorUid)

        // 恢复的软删除成员不加入 newMemberUids（避免重复计数），因此返回空列表
        assertEquals(emptyList<Long>(), invited)
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
        // leaveGroup 内部查询活跃成员数以判断是否解散群组
        coEvery { conversationMemberRepository.countActiveByConversationId(convId) } returns 2L

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
        // leaveGroup 内部查询活跃成员数以判断是否解散群组
        coEvery { conversationMemberRepository.countActiveByConversationId(convId) } returns 2L
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

    // =========================================================================
    // T04: memberCount 并发更新测试（MockK 方案，仅覆盖协程调度层）
    // =========================================================================

    /**
     * T04: memberCount 并发更新应保持协程调度一致性。
     *
     * 使用 MockK 模拟 Repository 层，验证 [ConversationService] 在协程并发场景下的调用逻辑正确性。
     * 本测试**仅覆盖协程并发调度逻辑**，不验证 MySQL JPA 层面的 member_count 原子一致性。
     * 真正的 MySQL 级并发一致性测试需 MySQL Testcontainers 环境，超出本阶段范围。
     *
     * 局限性：所有 Repository 调用被 MockK 替换后，coroutineScope { launch { ... } } 下的调用
     * 实际是顺序执行 MockK 预配置行为，无法验证 MySQL JPA 层的 member_count 原子性。
     * 此测试不替代 MySQL 级并发测试，仅为协程调度逻辑的轻量验证。
     */
    @Test
    fun memberCountConcurrentUpdatesShouldMaintainConsistency() = runTest {
        // Given: 设置 invite 和 leave 的 MockK 行为
        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        // invite: 操作者是群主
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid) } returns ownerMemberEntity
        // invite: 被邀请成员不在群中
        coEvery { conversationMemberRepository.findByConversationIdAndUserIds(convId, listOf(memberUid1)) } returns emptyList()
        // invite: 保存新成员记录
        coEvery { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        // invite: 成员计数增加
        coEvery { conversationRepository.incrementMemberCount(convId, 1) } returns 1
        // leave: 退群成员是已知普通成员
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        // leave: 查询活跃成员数（需要 > 1 避免触发解散逻辑）
        coEvery { conversationMemberRepository.countActiveByConversationId(convId) } returns 2L
        // leave: 软删除成员
        coEvery { conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1) } just Runs
        // leave: 成员计数减少
        coEvery { conversationRepository.incrementMemberCount(convId, -1) } returns -1

        // When: 并发执行 invite 和 leave
        coroutineScope {
            repeat(5) {
                launch {
                    service.inviteMember(
                        InviteMemberReq.newBuilder()
                            .setConversationId(convId)
                            .addAllUids(listOf(memberUid1))
                            .build(),
                        operatorUid
                    )
                }
                launch {
                    service.leaveGroup(
                        LeaveGroupReq.newBuilder()
                            .setConversationId(convId)
                            .build(),
                        memberUid1
                    )
                }
            }
        }

        // Then: 验证协程调度层执行完成且无异常抛出
        coVerify(atLeast = 5) { conversationRepository.incrementMemberCount(convId, 1) }
        coVerify(atLeast = 5) { conversationRepository.incrementMemberCount(convId, -1) }
    }

    // =========================================================================
    // dissolveGroup（P0-06）
    // =========================================================================

    /** 正常解散群组：标记 status=已解散 + 软删除所有成员 */
    @Test
    fun dissolveGroupShouldDeleteMembersAndSetConversationInactive() = runTest {
        // 模拟未被解散的群组会话
        val activeConv = ConversationEntity(type = 2, name = "测试群", memberCount = 3).apply {
            id = convId
            status = 0 // 活跃状态
            createdAt = now
            updatedAt = now
        }
        coEvery { conversationRepository.findById(convId) } returns Optional.of(activeConv)
        coEvery { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { conversationMemberRepository.softDeleteAllByConversationId(convId) } just Runs

        service.dissolveGroup(convId)

        // 验证会话状态已更新为已解散
        assertEquals(1, activeConv.status, "会话状态应标记为已解散")
        coVerify(exactly = 1) { conversationRepository.save(match { it.status == 1 }) }
        // 验证软删除所有成员
        coVerify(exactly = 1) {
            conversationMemberRepository.softDeleteAllByConversationId(convId)
        }
    }

    /** 解散已解散的群组时抛出 GROUP_DISSOLVED */
    @Test
    fun dissolveGroupShouldThrowWhenAlreadyDissolved() = runTest {
        val dissolvedConv = ConversationEntity(type = 2, name = "测试群", memberCount = 3).apply {
            id = convId
            status = 1 // 已解散
            createdAt = now
            updatedAt = now
        }
        coEvery { conversationRepository.findById(convId) } returns Optional.of(dissolvedConv)

        val ex = assertThrows<ConversationException> {
            service.dissolveGroup(convId)
        }
        assertEquals(BizCode.GROUP_DISSOLVED, ex.bizCode)
    }

    /** 不存在的群组解散时抛出 CONV_NOT_FOUND */
    @Test
    fun dissolveGroupShouldThrowWhenConvNotFound() = runTest {
        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val ex = assertThrows<ConversationException> {
            service.dissolveGroup("non-existent")
        }
        assertEquals(BizCode.CONV_NOT_FOUND, ex.bizCode)
    }

    // =========================================================================
    // getConversation / getConversationMembers / getMemberRole（P1-08）
    // =========================================================================

    /** getConversation 正常路径：返回 ConversationInfo */
    @Test
    fun getConversationShouldReturnConversation() = runTest {
        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)

        val result = service.getConversation(convId)

        assertNotNull(result, "应返回 ConversationInfo")
        assertEquals(convId, result.id)
        assertEquals(2, result.type, "群聊类型应为 2")
    }

    /** getConversation 不存在的会话返回 null */
    @Test
    fun getConversationShouldReturnNullWhenNotFound() = runTest {
        coEvery { conversationRepository.findById("non-existent") } returns Optional.empty()

        val result = service.getConversation("non-existent")

        assertNull(result, "不存在的会话应返回 null")
    }

    /** getConversationMembers 正常路径：返回成员列表 */
    @Test
    fun getConversationMembersShouldReturnMembers() = runTest {
        coEvery { conversationMemberRepository.findByConversationId(convId) } returns listOf(
            ownerMemberEntity, memberEntity1
        )

        val result = service.getConversationMembers(convId)

        assertEquals(2, result.size, "应返回 2 个成员")
        assertEquals(ownerUid, result[0].userId)
        assertEquals("owner", result[0].role)
        assertEquals(memberUid1, result[1].userId)
        assertEquals("member", result[1].role)
    }

    /** getConversationMembers 空成员返回空列表 */
    @Test
    fun getConversationMembersShouldReturnEmptyWhenNoMembers() = runTest {
        coEvery { conversationMemberRepository.findByConversationId(convId) } returns emptyList()

        val result = service.getConversationMembers(convId)

        assertTrue(result.isEmpty(), "无成员时应返回空列表")
    }

    /** getMemberRole 正常路径：返回成员角色 */
    @Test
    fun getMemberRoleShouldReturnRole() = runTest {
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, ownerUid) } returns ownerMemberEntity

        val result = service.getMemberRole(convId, ownerUid)

        assertNotNull(result, "应返回成员信息")
        assertEquals(ownerUid, result.userId)
        assertEquals("owner", result.role)
    }

    /** getMemberRole 不存在的成员返回 null */
    @Test
    fun getMemberRoleShouldReturnNullWhenMemberNotFound() = runTest {
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, 9999L) } returns null

        val result = service.getMemberRole(convId, 9999L)

        assertNull(result, "不存在的成员应返回 null")
    }

    // =========================================================================
    // leaveGroup memberCount==1 自动解散（P1-11）
    // =========================================================================

    /** 最后成员退群时自动解散群组（memberCount == 1L） */
    @Test
    fun leaveGroupShouldDissolveWhenLastMember() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        // 只有 1 个活跃成员 → 触发解散分支
        coEvery { conversationMemberRepository.countActiveByConversationId(convId) } returns 1L
        coEvery { conversationRepository.delete(any<ConversationEntity>()) } just Runs
        coEvery { conversationMemberRepository.delete(any<ConversationMemberEntity>()) } just Runs

        service.leaveGroup(req, memberUid1)

        coVerify(exactly = 1) {
            conversationRepository.delete(groupConversation)
            conversationMemberRepository.delete(memberEntity1)
        }
    }

    /** 多成员场景不退群时不触发解散（memberCount > 1L） */
    @Test
    fun leaveGroupShouldNotDissolveWhenMultipleMembers() = runTest {
        val req = LeaveGroupReq.newBuilder().setConversationId(convId).build()

        coEvery { conversationRepository.findById(convId) } returns Optional.of(groupConversation)
        coEvery { conversationMemberRepository.findByConversationIdAndUserId(convId, memberUid1) } returns memberEntity1
        // 多余 1 个活跃成员 → 进入软删除分支
        coEvery { conversationMemberRepository.countActiveByConversationId(convId) } returns 2L
        // memberEntity1.role = "member"（不是 owner）→ 继续执行软删除
        coEvery { conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1) } just Runs
        coEvery { conversationRepository.incrementMemberCount(convId, -1) } returns 1

        service.leaveGroup(req, memberUid1)

        // 验证走的是软删除路径而非解散路径
        coVerify(exactly = 1) {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, memberUid1)
            conversationRepository.incrementMemberCount(convId, -1)
        }
        // 验证未触发解散
        coVerify(exactly = 0) {
            conversationRepository.delete(any<ConversationEntity>())
            conversationMemberRepository.delete(any<ConversationMemberEntity>())
        }
    }
}
