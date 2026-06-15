package com.nebula.common.enum

/**
 * 会话状态枚举（CQ-12, L02）。
 *
 * 值定义与 SQL DDL 一致：0=活跃, 1=已解散。
 */
enum class ConversationStatus(val code: Int) {
    /** 活跃状态 */
    ACTIVE(0),
    /** 已解散 */
    DISMISSED(1);

    companion object {
        fun fromCode(code: Int): ConversationStatus = entries.first { it.code == code }
    }
}
