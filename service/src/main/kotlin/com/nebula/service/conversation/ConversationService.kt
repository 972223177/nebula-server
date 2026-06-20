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
import com.nebula.repository.dao.ConversationDao
import com.nebula.repository.dao.ConversationMemberDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.UserDao
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.isActive
import io.github.oshai.kotlinlogging.KotlinLogging
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
 *
 * 事务通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 */
class ConversationService(
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner
) {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
        /** 群聊最大成员数 */
        private const val MAX_MEMBERS = 200
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
        /** 群组已解散状态值（D-17: status=1） */
        private const val STATUS_DISSOLVED = 1
        /** 序列号恢复分页大小（D-81/H21）：每批扫描 500 条会话 */
        private const val RECOVERY_PAGE_SIZE = 500
        /** 序列号恢复安全网（D-81/H21）：最多扫描 10 万条，防止异常数据导致无限循环 */
        private const val RECOVERY_MAX_RECORDS = 100_000
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

        // 同一事务内原子写入：会话 + 群主 + 批量成员
        txRunner.execute { em ->
            conversationDao.insert(em, conv)
            conversationMemberDao.insert(em, ownerMember)
            memberEntities.forEach { conversationMemberDao.insert(em, it) }
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

        val (conversations, memberMap) = txRunner.execute { em ->
            val convs = conversationDao.findConversationsByUserId(em, userId, cursorDateTime, actualLimit + 1)
            val convIds = convs.map { requireNotNull(it.id) { "会话ID不能为null" } }
            val members = if (convIds.isNotEmpty()) {
                conversationMemberDao.findByConversationIdsAndUserId(em, convIds, userId)
                    .associateBy { it.conversationId }
            } else emptyMap()
            convs to members
        }

        val hasMore = conversations.size > actualLimit
        val result = if (hasMore) conversations.dropLast(1) else conversations

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
     * 流式分页获取所有未解散的会话（status=0）— 仅返回 (id, type) 元组（D-81/H21 序列号恢复）。
     *
     * 内部按 [RECOVERY_PAGE_SIZE] 分批调用 dao，避免一次性加载全表。
     * 调用方通过多次调用 nextBatch 推进游标直到返回空列表。
     *
     * @param offset 已跳过的记录数（首次传 0）
     * @return 当前页的 (conversationId, type) 元组列表，到达末尾时为空
     */
    suspend fun getActiveConversationsBatch(offset: Int): List<Pair<String, Int>> {
        return txRunner.execute { em ->
            conversationDao.findAllByStatus(
                em,
                status = 0, // 0=正常
                offset = offset,
                limit = RECOVERY_PAGE_SIZE
            ).map { entity -> requireNotNull(entity.id) to entity.type }
        }
    }

    /**
     * 获取所有未解散的会话（status=0）— 通过协程 Flow 暴露给 SeqService.recoverSequences。
     *
     * 使用 [getActiveConversationsBatch] 内部按 [RECOVERY_PAGE_SIZE] 分批拉取，
     * 避免内存溢出。仅在启动阶段（SeqService.recoverSequences）调用一次。
     *
     * @return 所有未解散会话的 (id, type) 元组序列
     */
    suspend fun getAllActiveConversations(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var offset = 0
        while (true) {
            val batch = getActiveConversationsBatch(offset)
            if (batch.isEmpty()) break
            result.addAll(batch)
            offset += batch.size
            // 安全网：单次启动阶段遍历超过 MAX_BATCHES 时强制退出，防止异常情况下无限循环
            if (offset > RECOVERY_MAX_RECORDS) {
                logger.warn { "序列号恢复超过 ${RECOVERY_MAX_RECORDS} 条，强制退出" }
                break
            }
        }
        logger.info { "扫描到 ${result.size} 个未解散会话用于序列号恢复" }
        return result
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
        val now = LocalDateTime.now()

        return txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 验证操作者是群主
            val operatorMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, operatorUid)
            if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可邀请成员")
            }

            val newMemberUids = mutableListOf<Long>()

            // D-83/M13: 前置批量查询替代 N+1 循环
            val existingMap = conversationMemberDao
                .findByConversationIdAndUserIds(em, convId, req.uidsList)
                .associateBy { it.userId }

            for (uid in req.uidsList) {
                val existing = existingMap[uid]
                if (existing != null && existing.isActive) continue // 已在群中

                if (existing != null && !existing.isActive) {
                    // 恢复已退出成员，不增加成员计数（原本已计入）
                    // 直接改字段，commit 时脏检查自动 UPDATE
                    existing.deleted = 0
                    existing.joinedAt = now
                    // 注意：不添加到 newMemberUids，避免重复计数
                } else {
                    // 创建新成员记录
                    val member = ConversationMemberEntity(
                        conversationId = convId,
                        userId = uid,
                        role = ROLE_MEMBER
                    )
                    member.joinedAt = now
                    conversationMemberDao.insert(em, member)
                    newMemberUids.add(uid)
                }
            }

            // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
            if (newMemberUids.isNotEmpty()) {
                conversationDao.incrementMemberCount(em, convId, newMemberUids.size)
            }
            newMemberUids
        }
    }

    /**
     * 退出群聊。
     *
     * @param req 退群请求
     * @param userId 当前用户 ID
     */
    suspend fun leaveGroup(req: LeaveGroupReq, userId: Long) {
        val convId = req.conversationId

        txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            val member = conversationMemberDao.findByConversationIdAndUserId(em, convId, userId)
                ?: throw ConversationException(BizCode.NOT_MEMBER)

            // 查询当前活跃成员数
            val memberCount = conversationMemberDao.countActiveByConversationId(em, convId)

            // 最后一个成员退群时，直接解散群组
            if (memberCount == 1L) {
                conversationDao.deleteById(em, convId)
                conversationMemberDao.delete(em, member)
                return@execute
            }

            // 多成员场景，群主退群逻辑
            if (member.role == ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "群主不能直接离开，请先转让群主或解散群组")
            }

            // 软删除成员记录
            conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, userId)

            // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
            conversationDao.incrementMemberCount(em, convId, -1)
        }
    }

    /**
     * 解散群组 — 群主操作，标记会话为已解散并软删除所有成员。
     *
     * 调用方（Handler）应在锁保护下调用此方法，
     * 确保 status 更新与成员删除的原子性。
     *
     * @param convId 群组会话 ID
     */
    suspend fun dissolveGroup(convId: String) {
        txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 检查群组是否已解散
            if (conv.status == STATUS_DISSOLVED) {
                throw ConversationException(BizCode.GROUP_DISSOLVED)
            }

            // 标记群组为已解散（直接改字段，commit 时脏检查自动 UPDATE）
            conv.status = STATUS_DISSOLVED

            // 软删除所有群成员
            conversationMemberDao.softDeleteAllByConversationId(em, convId)
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

        return txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 验证操作者是群主
            val operatorMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, operatorUid)
            if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可踢人")
            }

            // 不能踢群主
            val targetMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, targetUid)
                ?: throw ConversationException(BizCode.NOT_MEMBER)
            if (targetMember.role == ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "不能踢群主")
            }

            // 软删除被踢成员
            conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, targetUid)

            // D-82/H22: JPQL 原子更新替代非原子的 loadCount → set → save 模式
            conversationDao.incrementMemberCount(em, convId, -1)

            targetUid
        }
    }

    /**
     * 编辑群信息。
     *
     * @param req 编辑请求
     * @param operatorUid 操作者 UID
     */
    suspend fun editGroupInfo(req: EditGroupReq, operatorUid: Long) {
        val convId = req.conversationId

        txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 验证操作者是群主
            val member = conversationMemberDao.findByConversationIdAndUserId(em, convId, operatorUid)
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
            // 直接改字段，commit 时脏检查自动 UPDATE
        }
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

        // 单事务内完成：成员验证 + 成员列表 + 批量用户信息查询
        // 避免拆成两个事务导致的快照不一致问题
        val (members, userMap) = txRunner.execute { em ->
            // 验证成员身份
            val member = conversationMemberDao.findByConversationIdAndUserId(em, convId, userId)
            if (member == null || !member.isActive) {
                throw ConversationException(BizCode.NOT_MEMBER)
            }

            val allMembers = conversationMemberDao.findByConversationId(em, convId)
                .filter { it.isActive }
            val uidList = allMembers.map { it.userId }
            val users = if (uidList.isNotEmpty()) {
                userDao.findAllById(em, uidList).associateBy { it.id }
            } else emptyMap()
            allMembers to users
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

    /**
     * 根据会话 ID 查询会话信息，不存在时返回 null。
     *
     * 返回 [ConversationInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 id 和 type 字段。
     *
     * @param conversationId 会话 ID
     * @return 会话信息 DTO，不存在时返回 null
     */
    suspend fun getConversation(conversationId: String): ConversationInfo? {
        val entity = txRunner.execute { em -> conversationDao.findById(em, conversationId) }
            ?: return null
        return ConversationInfo(id = requireNotNull(entity.id), type = entity.type)
    }

    /**
     * 查询会话的所有成员列表。
     *
     * 返回 [ConversationMemberInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId 和 role 字段。
     *
     * @param conversationId 会话 ID
     * @return 会话成员信息 DTO 列表
     */
    suspend fun getConversationMembers(conversationId: String): List<ConversationMemberInfo> {
        val entities = txRunner.execute { em ->
            conversationMemberDao.findByConversationId(em, conversationId)
        }
        return entities.map { ConversationMemberInfo(userId = it.userId, role = it.role) }
    }

    /**
     * 查询指定用户在指定会话中的成员角色，不存在时返回 null。
     *
     * 返回 [ConversationMemberInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId 和 role 字段。
     *
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 成员信息 DTO，不存在时返回 null
     */
    suspend fun getMemberRole(conversationId: String, userId: Long): ConversationMemberInfo? {
        val entity = txRunner.execute { em ->
            conversationMemberDao.findByConversationIdAndUserId(em, conversationId, userId)
        } ?: return null
        return ConversationMemberInfo(userId = entity.userId, role = entity.role)
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
