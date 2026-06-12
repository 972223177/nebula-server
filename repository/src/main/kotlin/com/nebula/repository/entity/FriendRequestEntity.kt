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
    @Column(nullable = false)
    var fromUid: Long,

    @Column(nullable = false)
    var toUid: Long,

    var status: Int = 0
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
