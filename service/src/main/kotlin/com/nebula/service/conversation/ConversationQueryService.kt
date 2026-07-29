package com.nebula.service.conversation

import com.nebula.chat.conversation.*
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.repository.dao.*
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.isActive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 会话查询业务服务（D-02, D-05, D-10, D-19, D-81/H21）。
 *
 * 承接原单体 [ConversationService] 中「会话查询 / 列表 / 删除 / 私聊」子域：
 * 会话列表、群列表、序列号恢复扫描、会话与成员查询、按类型删除、私聊创建。
 *
 * 不依赖网关层组件（PushService、ConversationLockManager 等），并发控制与推送由调用方（Handler）负责。
 * 事务统一通过 [JpaTxRunner] 管理。依赖仅注入所需 DAO，**零循环依赖**：本服务不反向依赖 [GroupService]。
 */
class ConversationQueryService(
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val friendshipDao: FriendshipDao,
    private val txRunner: JpaTxRunner
) : ConversationQueryOperations {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}

        /** 序列号恢复分页大小（D-81/H21）：每批扫描 500 条会话 */
        private const val RECOVERY_PAGE_SIZE = 500

        /** 序列号恢复安全网（D-81/H21）：最多扫描 10 万条，防止异常数据导致无限循环 */
        private const val RECOVERY_MAX_RECORDS = 100_000
    }

    /**
     * 查询用户的会话列表（游标分页）。
     *
     * @param userId 当前用户 ID
     * @param cursor 游标（毫秒时间戳），0 表示首次查询
     * @param limit 每页条数
     * @return 会话列表响应
     */
    override suspend fun listConversations(userId: Long, cursor: Long, limit: Int): ConvListResp {
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

        // 私聊会话：批量查对方用户信息，填充 name 字段
        val privateConvIds = result.filter { it.type == CONV_TYPE_PRIVATE }.mapNotNull { it.id }
        // convId → 对方显示名（昵称优先，若无则用用户名）
        val (peerNameByConvId, selfOnlyConvIds) = if (privateConvIds.isNotEmpty()) {
            val allMembers = txRunner.execute { em ->
                conversationMemberDao.findAllByConversationIds(em, privateConvIds)
            }
            // convId → 对方 userId
            val peerUidByConvId = allMembers
                .filter { it.userId != userId }
                .associate { it.conversationId to it.userId }
            // 自会话防护：成员全部是自己的 private:uid:uid 异常会话，不展示
            val selfOnly = allMembers
                .groupBy { it.conversationId }
                .filter { (_, members) -> members.none { m -> m.userId != userId } }
                .keys
                .toSet()
            val peerUids = peerUidByConvId.values.distinct()
            val userMap = if (peerUids.isNotEmpty()) {
                txRunner.execute { em -> userDao.findAllById(em, peerUids) }
                    .associate { it.id to (it.nickname.ifBlank { it.username }) }
            } else emptyMap()
            // convId → 对方显示名
            peerUidByConvId.mapValues { (_, uid) -> userMap[uid] ?: "" } to selfOnly
        } else emptyMap<String, String>() to emptySet()

        // 过滤自会话(成员全是自己), 避免会话列表出现自己账户
        val displayResult = result.filterNot { it.id in selfOnlyConvIds }

        val builder = ConvListResp.newBuilder()
        displayResult.forEach { entity ->
            val member = memberMap[entity.id]
            val convId = requireNotNull(entity.id) { "会话ID不能为null" }
            // 私聊：用对方昵称/用户名填充 name；群聊：直接用 entity.name
            val displayName = if (entity.type == CONV_TYPE_PRIVATE) {
                peerNameByConvId[convId] ?: entity.name
            } else {
                entity.name
            }
            builder.addConversations(entity.toConversationBrief(
                displayName = displayName,
                lastReadMessageId = member?.lastReadMessageId ?: 0L
            ))
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
    override suspend fun getActiveConversationsBatch(offset: Int): List<Pair<String, Int>> {
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
    override suspend fun getAllActiveConversations(): List<Pair<String, Int>> {
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
     * 根据会话 ID 查询会话信息，不存在时返回 null。
     *
     * 返回 [ConversationInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 id 和 type 字段。
     *
     * @param conversationId 会话 ID
     * @return 会话信息 DTO，不存在时返回 null
     */
    override suspend fun getConversation(conversationId: String): ConversationInfo? {
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
    override suspend fun getConversationMembers(conversationId: String): List<ConversationMemberInfo> {
        val entities = txRunner.execute { em ->
            conversationMemberDao.findByConversationId(em, conversationId)
        }
        return entities.map { ConversationMemberInfo(userId = it.userId, role = it.role) }
    }

    /**
     * 查询用户参与的存活群组列表（conversation/group_list）。
     *
     * 仅返回 type=2(群聊) + status=0(正常) 的会话，过滤私聊和已解散群。
     * 复用 ConversationBrief，type 固定为 "group"。
     *
     * @param userId 用户 UID
     * @param cursor 游标（毫秒时间戳），0=首页
     * @param limit 每页条数
     * @return 群组列表响应
     */
    override suspend fun listGroupConversations(userId: Long, cursor: Long, limit: Int): GroupListResp {
        val actualLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val cursorDateTime = if (cursor == 0L) null
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor), ZoneOffset.UTC)

        val conversations = txRunner.execute { em ->
            conversationDao.findGroupConversationsByUserId(em, userId, cursorDateTime, actualLimit + 1)
        }
        val hasMore = conversations.size > actualLimit
        val result = if (hasMore) conversations.dropLast(1) else conversations

        val builder = GroupListResp.newBuilder()
        result.forEach { entity ->
            builder.addGroups(entity.toConversationBrief(
                displayName = entity.name,
                lastReadMessageId = 0L
            ))
        }
        builder.setHasMore(hasMore)
        return builder.build()
    }

    /**
     * 从列表软删除会话（conversation/delete）。
     *
     * 仅删除当前用户的 member 记录（deleted=1），不影响会话本身和其他成员。
     * 适用于私聊和群聊。
     *
     * @param userId 当前用户 UID
     * @param convId 会话 ID
     */
    override suspend fun deleteConversation(userId: Long, convId: String) {
        txRunner.execute { em ->
            conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, userId)
        }
    }

    /**
     * 按会话类型分支的删除行为（conversation/delete 业务编排）。
     *
     * 业务规则（按国内 IM 主流做法）：
     * - 私聊：仅软隐藏当前用户的 member 记录（不通知对方）
     * - 群聊：删除 = 退出群组
     *   - 群主：解散群（status=DISSOLVED + 软删除所有成员）
     *   - 普通成员：退群（软删除自己 + memberCount-1）
     *
     * 调用方（Handler）应在 `lockManager.withLock(convId)` 内调用此方法，
     * 保证"读 type/role → 写状态"的串行化，避免与踢人/退群并发冲突（D-19）。
     * 事务由本方法内部 `txRunner.execute` 管理。
     *
     * @param userId 当前用户 UID
     * @param convId 会话 ID
     * @return 实际执行的删除动作，供 Handler 选择推送事件类型
     */
    override suspend fun deleteConversationByType(userId: Long, convId: String): DeleteConversationAction {
        val action: DeleteConversationAction = txRunner.execute { em ->
            val conv = conversationDao.findById(em, convId)
                ?: throw ConversationException(BizCode.CONV_NOT_FOUND)

            when (conv.type) {
                CONV_TYPE_PRIVATE -> {
                    // 私聊：仅软隐藏当前用户
                    conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, userId)
                    DeleteConversationAction.PRIVATE_HIDDEN
                }
                CONV_TYPE_GROUP -> {
                    // 群聊：按角色路由
                    val member = conversationMemberDao.findByConversationIdAndUserId(em, convId, userId)
                        ?: throw ConversationException(BizCode.NOT_MEMBER)

                    when (member.role) {
                        ROLE_OWNER -> {
                            // 群主：解散群
                            if (conv.status == STATUS_DISSOLVED) {
                                // 已解散：仅软隐藏群主自己，幂等返回
                                conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, userId)
                                DeleteConversationAction.GROUP_DISSOLVED_ALREADY
                            } else {
                                conv.status = STATUS_DISSOLVED
                                conversationMemberDao.softDeleteAllByConversationId(em, convId)
                                DeleteConversationAction.GROUP_DISSOLVED
                            }
                        }
                        else -> {
                            // 普通成员：退群
                            val memberCount = conversationMemberDao.countActiveByConversationId(em, convId)
                            if (memberCount <= 1L) {
                                // 最后一个活跃成员：物理删除会话与成员记录（与 leaveGroup 兜底一致）
                                conversationDao.deleteById(em, convId)
                                conversationMemberDao.delete(em, member)
                            } else {
                                conversationMemberDao.softDeleteByConversationIdAndUserId(em, convId, userId)
                                conversationDao.incrementMemberCount(em, convId, -1)
                            }
                            DeleteConversationAction.GROUP_LEFT
                        }
                    }
                }
                else -> throw ConversationException(BizCode.CONV_NOT_FOUND, "不支持的会话类型: ${conv.type}")
            }
        }

        return action
    }

    /**
     * 创建或恢复到好友的私聊会话（conversation/create_private）。
     *
     * 流程：
     * 1. 校验双方是好友
     * 2. 生成固定 convId（private:<uid1>:<uid2>，较小的在前）
     * 3. 会话不存在则创建，存在则无需操作
     * 4. 恢复/创建双方的 member 记录（若已软删则恢复）
     *
     * @param userId 当前用户 UID
     * @param targetUid 对方用户 UID
     * @return 会话 ID
     */
    override suspend fun createPrivateConversation(userId: Long, targetUid: Long): String {
        if (userId == targetUid) {
            throw ConversationException(BizCode.INVALID_PARAM, "不能和自己创建私聊")
        }

        val smaller = minOf(userId, targetUid)
        val larger = maxOf(userId, targetUid)
        val convId = "private:$smaller:$larger"
        val now = LocalDateTime.now()

        txRunner.execute { em ->
            // 1. 校验好友关系
            val friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (friendship == null || !friendship.isActive) {
                throw ConversationException(BizCode.NOT_FRIEND, "私聊需要好友关系")
            }

            // 2. 创建/恢复会话
            var conv = conversationDao.findById(em, convId)
            if (conv == null) {
                conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
                conv.id = convId
                conv.createdAt = now
                conv.updatedAt = now
                conversationDao.insert(em, conv)
            }

            // 3. 恢复/创建双方 member 记录（需含软删，用于恢复）
            listOf(smaller, larger).forEach { uid ->
                val existing = conversationMemberDao.findByConversationIdAndUserIdIncludingDeleted(em, convId, uid)
                if (existing == null) {
                    val member = ConversationMemberEntity(conversationId = convId, userId = uid)
                    member.joinedAt = now
                    conversationMemberDao.insert(em, member)
                } else if (!existing.isActive) {
                    // 恢复软删记录
                    existing.deleted = 0
                    existing.joinedAt = now
                }
            }
        }

        return convId
    }
}
