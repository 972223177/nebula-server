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
    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false)
    var friendId: Long
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    var deleted: Int = 0
}
