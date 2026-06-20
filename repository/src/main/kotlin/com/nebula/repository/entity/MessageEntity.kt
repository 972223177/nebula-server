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
    /** 会话 ID，外键关联 conversations 表 */
    @Column(nullable = false, length = 32)
    var conversationId: String,

    /** 发送者用户 ID */
    @Column(nullable = false)
    var senderUid: Long,

    /** 消息类型编号 */
    var messageType: Int,

    /** 消息文本内容 */
    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String,

    /** 消息二进制载荷，如媒体文件元信息 */
    @Lob
    @Column(columnDefinition = "BLOB")
    var payload: ByteArray? = null,

    /** 客户端消息唯一标识，用于去重 */
    @Column(length = 64)
    var clientMessageId: String? = null,

    /** 客户端发送时间戳（毫秒） */
    var clientTs: Long,

    /** 服务端接收时间戳（毫秒） */
    var serverTs: Long
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
        senderUid = 0,
        messageType = 0,
        content = "",
        payload = null,
        clientMessageId = null,
        clientTs = 0,
        serverTs = 0
    )

    @Id
    @Column(nullable = false)
    var id: Long? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}
