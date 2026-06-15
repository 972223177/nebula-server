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
    /** 会话 ID */
    @Column(nullable = false, length = 32)
    var conversationId: String,

    /** 成员用户 ID */
    @Column(nullable = false)
    var userId: Long,

    /** 成员角色：owner=群主, member=普通成员（D-17） */
    @Column(nullable = false, length = 16)
    var role: String = "member",

    /** 最后已读消息 ID */
    @Column(nullable = false)
    var lastReadMessageId: Long = 0,

    /** 未读消息计数 */
    @Column(nullable = false)
    var unreadCount: Int = 0,

    /** 软删除标记：0=正常, 1=已退出/删除 */
    @Column(nullable = false)
    var deleted: Int = 0
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var joinedAt: LocalDateTime? = null
}

/**
 * 成员是否为活跃状态（未软删除）（D-86, CQ-15/L06）。
 *
 * 替代魔法数字 `deleted == 0` 的语义化访问。
 */
val ConversationMemberEntity.isActive: Boolean get() = deleted == 0
