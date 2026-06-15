package com.nebula.service.conversation

import com.nebula.chat.conversation.ConvListResp
import com.nebula.chat.conversation.ConversationBrief
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
import com.nebula.common.exception.ConversationException
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 会话业务服务（D-02, D-05, D-10, D-19）。
 *
 * 提供群组创建、成员管理、会话列表等业务逻辑。
 * 不依赖网关层组件（PushService、ConversationLockManager 等），
 * 并发控制和推送由调用方（Handler）负责。
 */
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val userRepository: UserRepository
) {

    companion object {
        /** 群聊最大成员数 */
        private const val MAX_MEMBERS = 200
        /** 私聊会话类型常量 */
        /** 私聊会话类型常量（CQ-12: 1=私聊，与 SQL DDL 一致） */
        private const val CONV_TYPE_PRIVATE = 1
        /** 群聊会话类型常量 */
        private const val CONV_TYPE_GROUP = 2
        /** 群主角色标识 */
        private const val ROLE_OWNER = "owner"
        /** 群成员角色标识 */
        private const val ROLE_MEMBER = "member"
        /** 会话列表单页最大条数 */
        private const val MAX_LIST_LIMIT = 50
    }

    /**
     * 创建群聊（D-02, D-05, D-10, D-19）。
     *
     * @param req 创建群请求
     * @param ownerUid 群主用户 ID
     * @return 创建结果（含 convId、name、成员列表）
     * @throws ConversationException 参数校验失败时
     */
    suspend fun createGroup(req: CreateGroupReq, ownerUid: Long): CreateGroupResult {
        val name = req.name.takeIf { it.isNotBlank() }
            ?: throw ConversationException(BizCode.INVALID_PARAM, "群名称不能为空")

        if (name.length > 128) {
            throw ConversationException(BizCode.INVALID_PARAM, "群名称不能超过128个字符")
        }

        if (ownerUid in req.memberUidsList) {
            throw ConversationException(BizCode.INVALID_PARAM, "创建者不能在初始成员列表中")
        }

        val totalMemberCount = 1 + req.memberUidsList.size
        if (totalMemberCount > MAX_MEMBERS) {
            throw ConversationException(BizCode.GROUP_FULL, "群成员数不能超过$MAX_MEMBERS")
        }

        val convId = UUID.randomUUID().toString()
        val now = LocalDateTime.now()

        // 创建群会话
        val conv = ConversationEntity(
            type = CONV_TYPE_GROUP,
            name = name,
            memberCount = totalMemberCount
        )
        conv.id = convId
        conv.createdAt = now
        conv.updatedAt = now

        // 创建群主成员记录
        val ownerMember = ConversationMemberEntity(
            conversationId = convId,
            userId = ownerUid,
            role = ROLE_OWNER
        )
        ownerMember.joinedAt = now

        // 批量创建初始成员记录
        val memberEntities = req.memberUidsList.map { uid ->
            val member = ConversationMemberEntity(
                conversationId = convId,
                userId = uid,
                role = ROLE_MEMBER
            )
            member.joinedAt = now
            member
        }

        withContext(Dispatchers.IO) {
            conversationRepository.save(conv)
            conversationMemberRepository.save(ownerMember)
            memberEntities.forEach { conversationMemberRepository.save(it) }
        }

        return CreateGroupResult(
            convId = convId,
            name = name,
            ownerUid = ownerUid,
            memberUids = req.memberUidsList
        )
    }

    /**
     * 查询用户的会话列表（游标分页）。
     *
     * @param userId 当前用户 ID
     * @param cursor 游标（毫秒时间戳），0 表示首次查询
     * @param limit 每页条数
     * @return 会话列表响应
     */
    suspend fun listConversations(userId: Long, cursor: Long, limit: Int): ConvListResp {
        val actualLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val cursorDateTime = if (cursor == 0L) null
            else LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor), ZoneOffset.UTC)

        val conversations = withContext(Dispatchers.IO) {
            conversationRepository.findConversationsByUserId(
                userId = userId,
                cursor = cursorDateTime,
                pageable = org.springframework.data.domain.PageRequest.of(0, actualLimit + 1)
            )
        }

        val hasMore = conversations.size > actualLimit
        val result = if (hasMore) conversations.dropLast(1) else conversations

        val convIds = result.map { requireNotNull(it.id) { "会话ID不能为null" } }
        val memberMap = if (convIds.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                conversationMemberRepository.findByConversationIdsAndUserId(convIds, userId)
            }.associateBy { it.conversationId }
        } else {
            emptyMap()
        }

        val builder = ConvListResp.newBuilder()
        result.forEach { entity ->
            val member = memberMap[entity.id]
            builder.addConversations(ConversationBrief.newBuilder()
                .setConversationId(requireNotNull(entity.id) { "会话ID不能为null" })
                .setType(if (entity.type == CONV_TYPE_PRIVATE) "private" else "group")
                .setName(entity.name)
                .setAvatarUrl(entity.avatar)
                .setLastMessageId(entity.lastMessageId)
                .setLastMessagePreview(entity.lastMessagePreview)
                .setLastMessageTs(entity.lastMessageTs)
                .setLastUpdatedAt(entity.updatedAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .setLastReadMsgId(member?.lastReadMessageId ?: 0)
                .build())
        }
        builder.setHasMore(hasMore)
        return builder.build()
    }

    /**
     * 邀请成员加入群聊。
     *
     * @param req 邀请请求
     * @param operatorUid 操作者 UID
     * @return 被邀请的成员 UID 列表
     */
    suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long> {
        val convId = req.conversationId
        val conv = withContext(Dispatchers.IO) { conversationRepository.findById(convId).orElse(null) }
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

        // 验证操作者是群主
        val operatorMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid)
        }
        if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可邀请成员")
        }

        val newMemberUids = mutableListOf<Long>()
        val now = LocalDateTime.now()

        // D-83/M13: 前置批量查询替代 N+1 循环
        val existingMap = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserIds(convId, req.uidsList)
        }.associateBy { it.userId }

        for (uid in req.uidsList) {
            val existing = existingMap[uid]
            if (existing != null && existing.deleted == 0) continue // 已在群中

            if (existing != null && existing.deleted == 1) {
                // 恢复已退出的成员
                existing.deleted = 0
                existing.joinedAt = now
                withContext(Dispatchers.IO) { conversationMemberRepository.save(existing) }
            } else {
                val member = ConversationMemberEntity(
                    conversationId = convId,
                    userId = uid,
                    role = ROLE_MEMBER
                )
                member.joinedAt = now
                withContext(Dispatchers.IO) { conversationMemberRepository.save(member) }
            }
            newMemberUids.add(uid)
        }

        // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
        withContext(Dispatchers.IO) {
            conversationRepository.incrementMemberCount(convId, newMemberUids.size)
        }

        return newMemberUids
    }

    /**
     * 退出群聊。
     *
     * @param req 退群请求
     * @param userId 当前用户 ID
     */
    suspend fun leaveGroup(req: LeaveGroupReq, userId: Long) {
        val convId = req.conversationId
        val conv = withContext(Dispatchers.IO) { conversationRepository.findById(convId).orElse(null) }
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, userId)
        }
        if (member == null) {
            throw ConversationException(BizCode.NOT_MEMBER)
        }

        // 群主不能退群（需要先转让群主）
        if (member.role == ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "群主不能退群，请先转让群主")
        }

        // 软删除成员记录
        withContext(Dispatchers.IO) {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, userId)
        }

        // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
        withContext(Dispatchers.IO) {
            conversationRepository.incrementMemberCount(convId, -1)
        }
    }

    /**
     * 踢出成员。
     *
     * @param req 踢人请求
     * @param operatorUid 操作者 UID
     * @return 被踢成员 UID
     */
    suspend fun kickMember(req: KickMemberReq, operatorUid: Long): Long {
        val convId = req.conversationId
        val targetUid = req.uid

        val conv = withContext(Dispatchers.IO) { conversationRepository.findById(convId).orElse(null) }
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

        // 验证操作者是群主
        val operatorMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid)
        }
        if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可踢人")
        }

        // 不能踢群主
        val targetMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, targetUid)
        }
        if (targetMember == null) {
            throw ConversationException(BizCode.NOT_MEMBER)
        }
        if (targetMember.role == ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "不能踢群主")
        }

        // 软删除被踢成员
        withContext(Dispatchers.IO) {
            conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, targetUid)
        }

        // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
        withContext(Dispatchers.IO) {
            conversationRepository.incrementMemberCount(convId, -1)
        }

        return targetUid
    }

    /**
     * 编辑群信息。
     *
     * @param req 编辑请求
     * @param operatorUid 操作者 UID
     */
    suspend fun editGroupInfo(req: EditGroupReq, operatorUid: Long) {
        val convId = req.conversationId
        val conv = withContext(Dispatchers.IO) { conversationRepository.findById(convId).orElse(null) }
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

        // 验证操作者是群主
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, operatorUid)
        }
        if (member == null || member.role != ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可编辑群信息")
        }

        if (req.hasName()) {
            if (req.name.isBlank()) {
                throw ConversationException(BizCode.INVALID_PARAM, "群名称不能为空")
            }
            conv.name = req.name
        }
        if (req.hasAvatarUrl()) {
            conv.avatar = req.avatarUrl
        }
        conv.updatedAt = LocalDateTime.now()

        withContext(Dispatchers.IO) { conversationRepository.save(conv) }
    }

    /**
     * 查询群成员列表。
     *
     * @param req 查询请求
     * @param userId 当前用户 ID
     * @return 群成员列表响应
     */
    suspend fun getGroupMembers(req: GroupMembersReq, userId: Long): GroupMembersResp {
        val convId = req.conversationId

        // 验证成员身份
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, userId)
        }
        if (member == null || member.deleted == 1) {
            throw ConversationException(BizCode.NOT_MEMBER)
        }

        val members = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationId(convId)
        }.filter { it.deleted == 0 }

        val uidList = members.map { it.userId }
        val userMap = if (uidList.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                userRepository.findAllById(uidList.map { it })
            }.associateBy { it.id }
        } else {
            emptyMap()
        }

        val builder = GroupMembersResp.newBuilder()
        members.forEach { m ->
            val user = userMap[m.userId]
            builder.addMembers(GroupMember.newBuilder()
                .setUid(m.userId)
                .setUsername(user?.username ?: "")
                .setDisplayName(user?.nickname ?: "")
                .setAvatarUrl(user?.avatar ?: "")
                .setRole(m.role)
                .setJoinedAt(m.joinedAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .build())
        }
        return builder.build()
    }
}

/**
 * 创建群聊结果。
 *
 * @param convId 会话 ID
 * @param name 群名称
 * @param ownerUid 群主 UID
 * @param memberUids 初始成员 UID 列表
 */
data class CreateGroupResult(
    val convId: String,
    val name: String,
    val ownerUid: Long,
    val memberUids: List<Long>
)
