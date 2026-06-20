package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 好友请求实体，映射 friend_requests 表。
 */
@Entity
@Table(name = "friend_requests", indexes = [
    Index(name = "idx_pending_requests", columnList = "to_uid, status")
])
class FriendRequestEntity(
    /** 申请发起方 UID */
    @Column(nullable = false)
    var fromUid: Long,

    /** 申请接收方 UID */
    @Column(nullable = false)
    var toUid: Long,

    /** 申请状态：0=待处理, 1=已接受, 2=已拒绝 */
    @Column(nullable = false)
    var status: Int = 0,

    /** 好友申请附言，D-42 */
    @Column(nullable = false, length = 255)
    var message: String = ""
) {
    /**
     * JPA 必需的受保护无参构造函数。
     *
     * refactor 后移除 kotlin-jpa/kotlin-allopen 插件，必须显式声明供 Hibernate 通过反射调用。
     * 字段保持默认空值，由 Hibernate 反序列化时通过 setter/反射填充。
     */
    @Suppress("unused")
    protected constructor() : this(
        fromUid = 0,
        toUid = 0,
        status = 0,
        message = ""
    )

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
