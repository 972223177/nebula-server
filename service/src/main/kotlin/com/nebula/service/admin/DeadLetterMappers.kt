package com.nebula.service.admin

import com.nebula.chat.admin.DeadLetterStatus
import com.nebula.repository.entity.DeadLetterEntity
import java.time.ZoneOffset

/**
 * 死信 Entity → [DeadLetterDTO] 映射（2026-07-29 从 [DeadLetterServiceImpl] 内联扩展迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * 处理 `createdAt` 的 LocalDateTime → 毫秒时间戳转换，以及 `id` 为 null 时的默认值。
 */

/**
 * 死信状态枚举 → 数据库存储值（小写 snake_case）。
 *
 * 枚举命名采用 SCREAMING_SNAKE，DB 列用其小写形式（如 `PENDING` → "pending"），故可直接 `name.lowercase()` 推导，无需手填映射表。
 * 该推导是唯一事实源：[DeadLetterEntity] 默认值与 [DeadLetterServiceImpl.STATUS_*] 常量均由此派生，杜绝字符串拼写漂移。
 */
fun DeadLetterStatus.toDbValue(): String = name.lowercase()

/**
 * 数据库存储值 → 死信状态枚举。
 *
 * 无法识别的值（含历史脏数据）回落为 [DeadLetterStatus.DEAD_LETTER_STATUS_UNKNOWN]，避免反序列化失败。
 */
fun String.toDeadLetterStatus(): DeadLetterStatus =
    DeadLetterStatus.entries.firstOrNull { it.toDbValue() == this } ?: DeadLetterStatus.DEAD_LETTER_STATUS_UNKNOWN

fun DeadLetterEntity.toDeadLetterDTO(): DeadLetterDTO =
    DeadLetterDTO(
        id = id ?: 0,
        msgId = msgId,
        conversationId = conversationId,
        senderUid = senderUid,
        failReason = failReason,
        failCount = failCount,
        status = status.toDeadLetterStatus(),
        createdAt = createdAt?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L
    )
