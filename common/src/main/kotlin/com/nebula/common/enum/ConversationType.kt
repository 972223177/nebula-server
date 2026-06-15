package com.nebula.common.enum

/**
 * 会话类型枚举（CQ-12, L02）。
 *
 * 值定义与 V1__init_schema.sql DDL COMMENT 一致：1=私聊, 2=群聊。
 * 使用 @JsonValue 确保 Jackson 序列化为数字而非字符串。
 */
enum class ConversationType(val code: Int) {
    /** 私聊会话 */
    PRIVATE(1),
    /** 群聊会话 */
    GROUP(2);

    companion object {
        /**
         * 从 code 值查找对应的枚举常量。
         *
         * @param code 会话类型编码
         * @return 对应的枚举常量
         * @throws NoSuchElementException 当 code 无匹配时
         */
        fun fromCode(code: Int): ConversationType = entries.first { it.code == code }
    }
}
