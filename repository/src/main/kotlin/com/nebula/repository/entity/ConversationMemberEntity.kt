package com.nebula.repository.entity

import com.nebula.chat.group.GroupMemberRole
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
    @Column(nullable = false, length = 64)
    var conversationId: String,

    /** 成员用户 ID */
    @Column(nullable = false)
    var userId: Long,

    /**
     * 成员角色（DB 存储值，小写 snake_case）。
     * 取值范围与协议层 [com.nebula.chat.group.GroupMemberRole] 一致，但 DB 列仅持久化 owner/member；
     * admin 为协议预留位，当前写入路径未启用，历史脏数据读取时由 `String.toGroupMemberRole()` 回落 UNKNOWN。
     * 默认值由枚举名推导，杜绝与枚举拼写漂移（D-17）。
     */
    @Column(nullable = false, length = 16)
    var role: String = GroupMemberRole.MEMBER.name.lowercase(),

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
    /**
     * JPA 必需的受保护无参构造函数。
     *
     * refactor 后移除 kotlin-jpa/kotlin-allopen 插件，必须显式声明供 Hibernate 通过反射调用。
     * 字段保持默认空值，由 Hibernate 反序列化时通过 setter/反射填充。
     */
    @Suppress("unused")
    protected constructor() : this(
        conversationId = "",
        userId = 0,
        role = GroupMemberRole.MEMBER.name.lowercase(),
        lastReadMessageId = 0,
        unreadCount = 0,
        deleted = 0
    )

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
