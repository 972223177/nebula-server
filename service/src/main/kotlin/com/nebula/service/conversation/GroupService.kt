package com.nebula.service.conversation

import com.nebula.chat.conversation.*
import com.nebula.chat.group.GroupMember
import com.nebula.chat.group.GroupMemberRole
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.common.util.toEpochMillis
import com.nebula.repository.dao.*
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.isActive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.util.*

/**
 * 群组业务服务（D-02, D-05, D-10, D-19）。
 *
 * 承接原单体 [ConversationService] 中「群生命周期 + 成员管理」子域：
 * 建群 / 编辑 / 解散 / 邀请 / 退群 / 踢人 / 成员查询 / 角色查询 / 活跃成员校验。
 *
 * 不依赖网关层组件（PushService、ConversationLockManager 等），并发控制与推送由调用方（Handler）负责。
 * 事务统一通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 * 依赖仅注入所需 DAO，**零循环依赖**：本服务不反向依赖 [ConversationQueryService]。
 */
class GroupService(
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner
) : GroupOperations {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}

        /** 群聊最大成员数 */
        private const val MAX_MEMBERS = 200
    }

    /**
     * 创建群聊（D-02, D-05, D-10, D-19）。
     *
     * @param req 创建群请求
     * @param ownerUid 群主用户 ID
     * @return 创建结果（含 convId、name、成员列表）
     * @throws ConversationException 参数校验失败时
     */
    override suspend fun createGroup(req: CreateGroupReq, ownerUid: Long): CreateGroupResult {
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
     * 邀请成员加入群聊。
     *
     * @param req 邀请请求
     * @param operatorUid 操作者 UID
     * @return 被邀请的成员 UID 列表
     */
    override suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long> {
        val convId = req.conversationId
        val now = LocalDateTime.now()

        return txRunner.execute { em ->
            // 守卫：确认会话存在，仅校验不取字段
            conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            // 验证操作者是群主
            val operatorMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, operatorUid)
            if (operatorMember == null || operatorMember.role != ROLE_OWNER) {
                throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可邀请成员")
            }

            val newMemberUids = mutableListOf<Long>()

            // D-83/M13: 前置批量查询（含软删，用于恢复已退出成员）
            val existingMap = conversationMemberDao
                .findByConversationIdAndUserIdsIncludingDeleted(em, convId, req.uidsList)
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
    override suspend fun leaveGroup(req: LeaveGroupReq, userId: Long) {
        val convId = req.conversationId

        txRunner.execute { em ->
            // 守卫：确认会话存在，仅校验不取字段
            conversationDao.findById(em, convId)
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
    override suspend fun dissolveGroup(convId: String) {
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
    override suspend fun kickMember(req: KickMemberReq, operatorUid: Long): Long {
        val convId = req.conversationId
        val targetUid = req.uid

        return txRunner.execute { em ->
            // 守卫：确认会话存在，仅校验不取字段
            conversationDao.findById(em, convId)
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
    override suspend fun editGroupInfo(req: EditGroupReq, operatorUid: Long) {
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
    override suspend fun getGroupMembers(req: GroupMembersReq, userId: Long): GroupMembersResp {
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
                .setRole(m.role.toGroupMemberRole())
                .setJoinedAt(m.joinedAt?.toEpochMillis() ?: 0)
                .build())
        }
        return builder.build()
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
    override suspend fun getMemberRole(conversationId: String, userId: Long): ConversationMemberInfo? {
        val entity = txRunner.execute { em ->
            conversationMemberDao.findByConversationIdAndUserId(em, conversationId, userId)
        } ?: return null
        return ConversationMemberInfo(userId = entity.userId, role = entity.role.toGroupMemberRole())
    }

    /**
     * 检查用户是否是指定会话的活跃成员（用于 chat/send 懒加载前置判断，2026-07 改造）。
     *
     * 与 [getMemberRole] 的区别：直接返回 Boolean，避免 gateway 层做 null 判空；
     * DAO 查询自带 `deleted = 0` 过滤，**软删的 member 不算活跃**。
     *
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 是活跃成员返回 true，会话不存在 / 不是成员 / 软删 / 异常均返回 false
     */
    @Suppress("unused")
    override suspend fun requireMemberActive(conversationId: String, userId: Long): Boolean {
        return try {
            txRunner.execute { em ->
                conversationMemberDao.findByConversationIdAndUserId(em, conversationId, userId)?.isActive ?: false
            }
        } catch (e: Exception) {
            logger.warn(e) { "requireMemberActive 查询失败: convId=$conversationId, userId=$userId" }
            false
        }
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
