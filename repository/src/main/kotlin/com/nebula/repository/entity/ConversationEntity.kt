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
    /** 会话类型：0=私聊, 2=群聊 */
    @Column(nullable = false)
    var type: Int,

    /** 群组名称 */
    @Column(nullable = false, length = 128)
    var name: String = "",

    /** 群组头像 URL */
    @Column(nullable = false, length = 256)
    var avatar: String = "",

    /** 群主用户 ID */
    var groupOwnerUid: Long? = null,

    /** 群成员计数 */
    @Column(nullable = false)
    var memberCount: Int = 0,

    /** 群成员上限 */
    @Column(nullable = false)
    var maxMembers: Int = 200,

    /** 会话状态：0=正常, 1=已解散（D-17） */
    @Column(nullable = false)
    var status: Int = 0,

    /** 最后一条消息的 Snowflake ID（D-21） */
    @Column(nullable = false)
    var lastMessageId: Long = 0,

    /** 最后一条消息的文本预览（D-21），最多 100 字符 */
    @Column(nullable = false, length = 100)
    var lastMessagePreview: String = "",

    /** 最后一条消息的客户端时间戳，单位 ms（D-21） */
    @Column(nullable = false)
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
