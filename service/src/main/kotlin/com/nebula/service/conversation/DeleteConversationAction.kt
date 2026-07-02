package com.nebula.service.conversation

/**
 * 删除会话动作结果（conversation/delete 业务编排返回值）。
 *
 * 由 [ConversationService.deleteConversationByType] 返回，
 * 供 Handler 选择对应的推送事件类型。
 */
enum class DeleteConversationAction {
    /** 私聊：仅软隐藏当前用户，不推送 */
    PRIVATE_HIDDEN,

    /** 群聊：普通成员退群成功，需要推 MEMBER_LEFT */
    GROUP_LEFT,

    /** 群聊：群主解散群成功，需要推 GROUP_DISSOLVED */
    GROUP_DISSOLVED,

    /** 群聊：群已被解散（幂等），仅软隐藏群主自己，不推送 */
    GROUP_DISSOLVED_ALREADY
}
