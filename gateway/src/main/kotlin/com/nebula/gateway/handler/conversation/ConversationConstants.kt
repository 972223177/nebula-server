package com.nebula.gateway.handler.conversation

/**
 * 会话类型常量定义。
 *
 * 由 FriendAcceptHandler 和 ConversationService 共享引用。
 */
object ConversationConstants {
    /** 私聊会话类型（D-43） */
    const val CONV_TYPE_PRIVATE = 1

    /** 群聊会话类型（Phase 7 群聊常量迁移至此） */
    const val CONV_TYPE_GROUP = 2
}
