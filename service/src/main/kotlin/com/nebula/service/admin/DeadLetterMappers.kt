package com.nebula.service.admin

import com.nebula.repository.entity.DeadLetterEntity
import java.time.ZoneOffset

/**
 * 死信 Entity → [DeadLetterDTO] 映射（2026-07-29 从 [DeadLetterServiceImpl] 内联扩展迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * 处理 `createdAt` 的 LocalDateTime → 毫秒时间戳转换，以及 `id` 为 null 时的默认值。
 */
fun DeadLetterEntity.toDeadLetterDTO(): DeadLetterDTO =
    DeadLetterDTO(
        id = id ?: 0,
        msgId = msgId,
        conversationId = conversationId,
        senderUid = senderUid,
        failReason = failReason,
        failCount = failCount,
        status = status,
        createdAt = createdAt?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L
    )
