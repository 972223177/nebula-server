package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 会话实体，映射 conversations 表。
 *
 * id 使用 UUID（String 类型），由 Service 层生成。
 * D-17 新增会话状态（status）、D-21 新增最后消息快照字段。
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

    var maxMembers: Int = 200,

    /** 会话状态：0=正常, 1=已解散（D-17） */
    var status: Int = 0,

    /** 最后一条消息的 Snowflake ID（D-21） */
    var lastMessageId: Long = 0,

    /** 最后一条消息的文本预览（D-21），最多 100 字符 */
    @Column(length = 100)
    var lastMessagePreview: String = "",

    /** 最后一条消息的客户端时间戳，单位 ms（D-21） */
    var lastMessageTs: Long = 0
) {
    @Id
    @Column(length = 32)
    var id: String? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
