package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 好友关系实体，映射 friendships 表。
 */
@Entity
@Table(name = "friendships", indexes = [
    Index(name = "uk_friendship", columnList = "user_id, friend_id", unique = true),
    Index(name = "idx_friends", columnList = "friend_id")
])
class FriendshipEntity(
    /** 用户 ID */
    @Column(nullable = false)
    var userId: Long,

    /** 好友用户 ID */
    @Column(nullable = false)
    var friendId: Long
) {
    /**
     * JPA 必需的受保护无参构造函数。
     *
     * refactor 后移除 kotlin-jpa/kotlin-allopen 插件，必须显式声明供 Hibernate 通过反射调用。
     * 字段保持默认空值，由 Hibernate 反序列化时通过 setter/反射填充。
     */
    @Suppress("unused")
    protected constructor() : this(
        userId = 0,
        friendId = 0
    )

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    /** 软删除标记：0=正常, 1=已删除 */
    var deleted: Int = 0
}

/**
 * 好友关系是否有效（未软删除）（D-86, CQ-15/L05）。
 *
 * 替代魔法数字 `deleted == 0` 的语义化访问。
 */
val FriendshipEntity.isActive: Boolean get() = deleted == 0
