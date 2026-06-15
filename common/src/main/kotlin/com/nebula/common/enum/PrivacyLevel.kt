package com.nebula.common.enum

/**
 * 隐私级别枚举（CQ-12, L03）。
 *
 * 对应 UserEntity.privacyStatus 字段。
 * 值定义：0=公开, 1=仅好友可见, 2=隐藏。
 */
enum class PrivacyLevel(val code: Int) {
    /** 公开 */
    PUBLIC(0),
    /** 仅好友可见 */
    FRIENDS_ONLY(1),
    /** 隐藏 */
    PRIVATE(2);

    companion object {
        fun fromCode(code: Int): PrivacyLevel = entries.first { it.code == code }
    }
}
