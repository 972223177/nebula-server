package com.nebula.service.admin

/**
 * 死信数据传输对象 — 用于 service 层对外暴露死信数据，
 * 避免 gateway 层直接依赖 repository 层的实体类。
 *
 * D-28: 通过 DTO 解耦 service 与 gateway，gateway 不感知 JPA 实体细节。
 */
data class DeadLetterDTO(
    /** 死信 ID */
    val id: Long,
    /** 关联消息 ID */
    val msgId: Long?,
    /** 会话 ID */
    val conversationId: String,
    /** 发送者 UID */
    val senderUid: Long,
    /** 失败原因 */
    val failReason: String,
    /** 重试次数 */
    val failCount: Int,
    /** 当前状态 */
    val status: String,
    /** 创建时间 — 毫秒时间戳 */
    val createdAt: Long
)
