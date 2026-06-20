package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 死信实体，映射 dead_letters 表（Phase 10）。
 *
 * 记录因投递失败而进入死信队列的消息，支持重试、补偿和永久失败标记。
 * 使用 @Version 乐观锁控制并发补偿时的竞争更新（D-76）。
 *
 * @param msgId 原始消息 ID（可为空，如非 ChatMessage 类型的死信）
 * @param conversationId 会话 ID
 * @param senderUid 发送者用户 ID
 * @param messageType 消息类型
 * @param content 消息内容文本
 * @param payload 消息序列化字节（Protobuf 二进制）
 * @param clientMsgId 客户端消息 ID，用于去重
 * @param clientTs 客户端时间戳
 * @param failReason 失败原因描述
 * @param failCount 失败重试计数，初始为 0
 * @param status 死信状态：pending / retrying / permanent_failed / retry_success
 * @param version 乐观锁版本号，@Version 自动管理
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
@Entity
@Table(name = "dead_letters", indexes = [
    Index(name = "idx_status_created", columnList = "status, created_at"),
    Index(name = "idx_client_msg_id", columnList = "client_msg_id")
])
class DeadLetterEntity(
    @Column(length = 64, nullable = false)
    var conversationId: String,

    @Column(nullable = false)
    var senderUid: Long,

    @Column(nullable = false)
    var messageType: Int,

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String,

    @Lob
    @Column(columnDefinition = "BLOB")
    var payload: ByteArray? = null,

    @Column(name = "client_msg_id", length = 64)
    var clientMsgId: String? = null,

    @Column(name = "client_ts", nullable = false)
    var clientTs: Long,

    @Column(length = 256, nullable = false)
    var failReason: String = "",

    @Column(nullable = false)
    var failCount: Int = 0,

    @Column(length = 32, nullable = false)
    var status: String = "pending"
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
        clientMsgId = null,
        clientTs = 0,
        failReason = "",
        failCount = 0,
        status = "pending"
    )

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var id: Long? = null

    /** 原始消息 ID（非必需字段） */
    var msgId: Long? = null

    /** 乐观锁版本号（D-76） */
    @Version
    @Column(nullable = false)
    var version: Int? = null

    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
