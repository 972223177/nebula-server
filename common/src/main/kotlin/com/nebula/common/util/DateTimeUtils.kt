package com.nebula.common.util

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * LocalDateTime → epoch millis 转换工具。
 *
 * L6 规范化：消除 FriendService 和 ConversationService 中的重复转换逻辑。
 */
fun LocalDateTime.toEpochMillis(): Long {
    return this.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
}
