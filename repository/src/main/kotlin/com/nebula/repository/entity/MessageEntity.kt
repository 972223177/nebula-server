package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 消息实体，映射 messages 表。
 *
 * id 使用 Snowflake 算法生成，天然时间有序，适合游标分页。
 */
@Entity
@Table(name = "messages", indexes = [
    Index(name = "idx_conv_messages", columnList = "conversation_id, id"),
    Index(name = "uk_client_msg_id", columnList = "client_message_id", unique = true)
])
class MessageEntity(
    @Column(nullable = false, length = 32)
    var conversationId: String,

    @Column(nullable = false)
    var senderUid: Long,

    var messageType: Int,

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String,

    @Lob
    @Column(columnDefinition = "BLOB")
    var payload: ByteArray? = null,

    @Column(length = 64)
    var clientMessageId: String? = null,

    var clientTs: Long,

    var serverTs: Long
) {
    @Id
    @Column(nullable = false)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}
