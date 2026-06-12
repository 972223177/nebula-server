package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 会话实体，映射 conversations 表。
 *
 * id 使用 UUID（String 类型），由 Service 层生成。
 */
@Entity
@Table(name = "conversations")
class ConversationEntity(
    @Column(nullable = false)
    var type: Int,

    @Column(length = 128)
    var name: String = "",

    @Column(length = 256)
    var avatar: String = "",

    var groupOwnerUid: Long? = null,

    var memberCount: Int = 0,

    var maxMembers: Int = 200
) {
    @Id
    @Column(length = 32)
    var id: String? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
