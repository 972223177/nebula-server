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
}
