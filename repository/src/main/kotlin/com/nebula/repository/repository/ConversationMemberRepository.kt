package com.nebula.repository.repository

import com.nebula.repository.entity.ConversationMemberEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 会话成员数据仓库。
 */
interface ConversationMemberRepository : JpaRepository<ConversationMemberEntity, Long> {
    /** 按会话查找所有成员 */
    fun findByConversationId(conversationId: String): List<ConversationMemberEntity>
    /** 按会话和用户查找成员记录 */
    fun findByConversationIdAndUserId(conversationId: String, userId: Long): ConversationMemberEntity?
    /** 查询用户参与的所有会话 */
    fun findByUserId(userId: Long): List<ConversationMemberEntity>

    /**
     * 递增会话中除发送者外所有成员的未读计数（DB-06）。
     *
     * 消息发送时由 Service 层调用（D-09：事务在 Service 层管理）。
     *
     * @param conversationId 会话 ID
     * @param senderId 消息发送者用户 ID（不递增其未读计数）
     */
    @Modifying
    @Query("""
        UPDATE ConversationMemberEntity cm
        SET cm.unreadCount = cm.unreadCount + 1
        WHERE cm.conversationId = :convId AND cm.userId <> :senderId
    """)
    fun incrementUnreadCount(
        @Param("convId") conversationId: String,
        @Param("senderId") senderId: Long
    )

    /**
     * 更新已读回执：设置 last_read_message_id 并清零 unread_count（DB-07）。
     *
     * @param conversationId 会话 ID
     * @param userId 发起已读回执的用户 ID
     * @param lastReadMsgId 已读的最后一条消息 ID
     */
    @Modifying
    @Query("""
        UPDATE ConversationMemberEntity cm
        SET cm.lastReadMessageId = :lastReadMsgId,
            cm.unreadCount = 0
        WHERE cm.conversationId = :convId AND cm.userId = :userId
    """)
    fun updateReadReceipt(
        @Param("convId") conversationId: String,
        @Param("userId") userId: Long,
        @Param("lastReadMsgId") lastReadMsgId: Long
    )

    /**
     * 统计会话中活跃成员数（排除已软删除的成员）。
     *
     * @param conversationId 会话 ID
     * @return 活跃成员数
     */
    @Query("""
        SELECT COUNT(cm) FROM ConversationMemberEntity cm
        WHERE cm.conversationId = :convId AND cm.deleted = 0
    """)
    fun countActiveByConversationId(@Param("convId") conversationId: String): Long

    /**
     * 软删除指定用户在指定会话中的成员记录（退群/踢人）。
     *
     * @param conversationId 会话 ID
     * @param userId 用户 ID
     */
    @Modifying
    @Query("""
        UPDATE ConversationMemberEntity cm
        SET cm.deleted = 1
        WHERE cm.conversationId = :convId AND cm.userId = :userId
    """)
    fun softDeleteByConversationIdAndUserId(
        @Param("convId") conversationId: String,
        @Param("userId") userId: Long
    )

    /**
     * 批量查找会话中指定用户 ID 列表的成员记录（用于重复邀请检测）。
     *
     * @param conversationId 会话 ID
     * @param userIds 用户 ID 列表
     * @return 匹配的成员记录列表（仅包含传入 uid 中已存在的）
     */
    @Query("""
        SELECT cm FROM ConversationMemberEntity cm
        WHERE cm.conversationId = :convId AND cm.userId IN :userIds
    """)
    fun findByConversationIdAndUserIds(
        @Param("convId") conversationId: String,
        @Param("userIds") userIds: List<Long>
    ): List<ConversationMemberEntity>

    /**
     * 批量查询用户在各会话中的成员记录（用于会话列表批量获取 lastReadMsgId）。
     *
     * @param conversationIds 会话 ID 列表
     * @param userId 用户 ID
     * @return 匹配的成员记录列表
     */
    @Query("""
        SELECT cm FROM ConversationMemberEntity cm
        WHERE cm.conversationId IN :convIds AND cm.userId = :userId
    """)
    fun findByConversationIdsAndUserId(
        @Param("convIds") conversationIds: List<String>,
        @Param("userId") userId: Long
    ): List<ConversationMemberEntity>

    /**
     * 批量软删除会话中所有成员记录（群主退群时解散群）。
     *
     * @param conversationId 会话 ID
     */
    @Modifying
    @Query("""
        UPDATE ConversationMemberEntity cm
        SET cm.deleted = 1
        WHERE cm.conversationId = :convId
    """)
    fun softDeleteAllByConversationId(@Param("convId") conversationId: String)
}
