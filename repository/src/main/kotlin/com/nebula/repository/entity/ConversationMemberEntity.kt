package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 会话成员实体，映射 conversation_members 表。
 *
 * 维护每个成员在会话中的未读计数和已读回执位置。
 */
@Entity
@Table(name = "conversation_members", indexes = [
    Index(name = "uk_member", columnList = "conversation_id, user_id", unique = true),
    Index(name = "idx_user_convs", columnList = "user_id")
])
class ConversationMemberEntity(
    @Column(nullable = false, length = 32)
    var conversationId: String,

    @Column(nullable = false)
    var userId: Long,

    /** 成员角色：owner=群主, member=普通成员（D-17） */
    @Column(length = 16)
    var role: String = "member",

    var lastReadMessageId: Long = 0,

    var unreadCount: Int = 0,

    var deleted: Int = 0
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var joinedAt: LocalDateTime? = null
}
