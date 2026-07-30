package com.nebula.repository.dao

import com.nebula.repository.entity.ConversationMemberEntity
import jakarta.persistence.EntityManager

/**
 * 会话成员数据访问对象（替代原 ConversationMemberRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 */
class ConversationMemberDao : EntityDao<ConversationMemberEntity>(ConversationMemberEntity::class.java) {

    /**
     * 按会话查找所有成员。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @return 成员记录列表
     */
    fun findByConversationId(
        em: EntityManager,
        conversationId: String
    ): List<ConversationMemberEntity> = queryList(
        em,
        "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId",
        "convId" to conversationId
    )

    /**
     * 按会话和用户查找成员记录。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 成员实体，不存在返回 null
     */
    /**
     * 按会话和用户查成员（仅活跃记录，deleted=0）。
     *
     * 修复：加上 deleted=0 条件，避免 message/pull 等成员校验返回已软删记录，
     * 导致 conversation/list 显示会话但 message/pull 报 1403 NOT_MEMBER。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 活跃的成员实体，软删或不存在返回 null
     */
    fun findByConversationIdAndUserId(
        em: EntityManager,
        conversationId: String,
        userId: Long
    ): ConversationMemberEntity? = querySingle(
        em,
        "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId AND cm.deleted = 0",
        "convId" to conversationId,
        "userId" to userId
    )

    /**
     * 查会话成员（含软删），用于 createPrivateConversation / invite 等恢复场景。
     */
    fun findByConversationIdAndUserIdIncludingDeleted(
        em: EntityManager,
        conversationId: String,
        userId: Long
    ): ConversationMemberEntity? = querySingle(
        em,
        "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId",
        "convId" to conversationId,
        "userId" to userId
    )

    /**
     * 查询用户参与的所有会话成员记录。
     *
     * @param em 当前事务的 [EntityManager]
     * @param userId 用户 ID
     * @return 成员记录列表
     */
    fun findByUserId(
        em: EntityManager,
        userId: Long
    ): List<ConversationMemberEntity> = queryList(
        em,
        "SELECT cm FROM ConversationMemberEntity cm WHERE cm.userId = :userId",
        "userId" to userId
    )

    /**
     * 递增会话中除发送者外所有成员的未读计数（DB-06）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param senderId 消息发送者用户 ID（不递增其未读计数）
     * @return 受影响的行数
     */
    fun incrementUnreadCount(
        em: EntityManager,
        conversationId: String,
        senderId: Long
    ): Int = executeUpdate(
        em,
        """
        UPDATE ConversationMemberEntity cm
        SET cm.unreadCount = cm.unreadCount + 1
        WHERE cm.conversationId = :convId AND cm.userId <> :senderId
        """.trimIndent(),
        "convId" to conversationId,
        "senderId" to senderId
    )

    /**
     * 幂等递增会话中除发送者外所有成员的未读计数（§七 Durable Outbox）。
     *
     * 通过 [unread_dedup] 表（PK(conv_id, msg_id)）的 INSERT IGNORE 保证每条消息的未读自增
     * 在进程崩溃 / PEL 重投下最多生效一次：
     * - INSERT IGNORE 返回 1 → 首次处理，执行未读 +1；
     * - INSERT IGNORE 返回 0 → 已处理过，跳过（避免双计）。
     * 两步在同一事务内（调用方 [JpaTxRunner]），若 UPDATE 抛异常整体回滚，重试时可重新 claim。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param msgId 消息 ID（幂等键组成部分）
     * @param senderId 消息发送者用户 ID（不递增其未读计数）
     * @return true 表示本次实际执行了未读自增，false 表示已处理过（幂等跳过）
     */
    fun incrementUnreadCountIdempotent(
        em: EntityManager,
        conversationId: String,
        msgId: Long,
        senderId: Long
    ): Boolean {
        val claimed = em.createNativeQuery(
            "INSERT IGNORE INTO unread_dedup(conv_id, msg_id) VALUES(:convId, :msgId)"
        ).setParameter("convId", conversationId)
            .setParameter("msgId", msgId)
            .executeUpdate()
        if (claimed == 1) {
            executeUpdate(
                em,
                """
                UPDATE ConversationMemberEntity cm
                SET cm.unreadCount = cm.unreadCount + 1
                WHERE cm.conversationId = :convId AND cm.userId <> :senderId
                """.trimIndent(),
                "convId" to conversationId,
                "senderId" to senderId
            )
            return true
        }
        return false
    }

    /**
     * 更新已读回执：设置 last_read_message_id 并清零 unread_count（DB-07）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param userId 发起已读回执的用户 ID
     * @param lastReadMsgId 已读的最后一条消息 ID
     * @return 受影响的行数
     */
    fun updateReadReceipt(
        em: EntityManager,
        conversationId: String,
        userId: Long,
        lastReadMsgId: Long
    ): Int = executeUpdate(
        em,
        """
        UPDATE ConversationMemberEntity cm
        SET cm.lastReadMessageId = :lastReadMsgId,
            cm.unreadCount = 0
        WHERE cm.conversationId = :convId AND cm.userId = :userId
        """.trimIndent(),
        "lastReadMsgId" to lastReadMsgId,
        "convId" to conversationId,
        "userId" to userId
    )

    /**
     * 统计会话中活跃成员数（排除已软删除的成员）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @return 活跃成员数
     */
    fun countActiveByConversationId(
        em: EntityManager,
        conversationId: String
    ): Long = count(
        em,
        "SELECT COUNT(cm) FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.deleted = 0",
        "convId" to conversationId
    )

    /**
     * 软删除指定用户在指定会话中的成员记录（退群/踢人）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     * @return 受影响的行数
     */
    fun softDeleteByConversationIdAndUserId(
        em: EntityManager,
        conversationId: String,
        userId: Long
    ): Int = executeUpdate(
        em,
        """
        UPDATE ConversationMemberEntity cm
        SET cm.deleted = 1
        WHERE cm.conversationId = :convId AND cm.userId = :userId
        """.trimIndent(),
        "convId" to conversationId,
        "userId" to userId
    )

    /**
     * 批量查找会话中指定用户 ID 列表的成员记录（用于重复邀请检测）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param userIds 用户 ID 列表
     * @return 匹配的成员记录列表（仅包含传入 uid 中已存在的）
     */
    /**
     * 批量查会话成员（仅活跃，用于 conversation/list 等）。*/
    fun findByConversationIdAndUserIds(
        em: EntityManager,
        conversationId: String,
        userIds: List<Long>
    ): List<ConversationMemberEntity> {
        if (userIds.isEmpty()) return emptyList()
        return queryList(
            em,
            "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId IN :userIds AND cm.deleted = 0",
            "convId" to conversationId,
            "userIds" to userIds
        )
    }

    /**
     * 批量查会话成员（含软删，用于 invite 恢复已退出成员场景）。
     */
    fun findByConversationIdAndUserIdsIncludingDeleted(
        em: EntityManager,
        conversationId: String,
        userIds: List<Long>
    ): List<ConversationMemberEntity> {
        if (userIds.isEmpty()) return emptyList()
        return queryList(
            em,
            "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId IN :userIds",
            "convId" to conversationId,
            "userIds" to userIds
        )
    }

    /**
     * 批量查询用户在各会话中的成员记录（用于会话列表批量获取 lastReadMsgId）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationIds 会话 ID 列表
     * @param userId 用户 ID
     * @return 匹配的成员记录列表
     */
    fun findByConversationIdsAndUserId(
        em: EntityManager,
        conversationIds: List<String>,
        userId: Long
    ): List<ConversationMemberEntity> {
        if (conversationIds.isEmpty()) return emptyList()
        return queryList(
            em,
            "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId IN :convIds AND cm.userId = :userId",
            "convIds" to conversationIds,
            "userId" to userId
        )
    }

    /**
     * 按会话 ID 列表查所有成员（不区分 userId），用于批量获取对方信息。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationIds 会话 ID 列表
     * @return 所有成员实体列表
     */
    fun findAllByConversationIds(
        em: EntityManager,
        conversationIds: List<String>
    ): List<ConversationMemberEntity> {
        if (conversationIds.isEmpty()) return emptyList()
        return queryList(
            em,
            "SELECT cm FROM ConversationMemberEntity cm WHERE cm.conversationId IN :convIds AND cm.deleted = 0",
            "convIds" to conversationIds
        )
    }

    /**
     * 批量软删除会话中所有成员记录（群主退群时解散群）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @return 受影响的行数
     */
    fun softDeleteAllByConversationId(
        em: EntityManager,
        conversationId: String
    ): Int = executeUpdate(
        em,
        """
        UPDATE ConversationMemberEntity cm
        SET cm.deleted = 1
        WHERE cm.conversationId = :convId
        """.trimIndent(),
        "convId" to conversationId
    )
}
