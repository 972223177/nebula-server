package com.nebula.service.conversation

/**
 * 会话信息 DTO — 供 gateway 层查询会话基本信息，替代 [com.nebula.repository.entity.ConversationEntity]。
 *
 * 屏蔽 repository 层的 JPA 实体细节，仅暴露 gateway 层需要的业务字段。
 *
 * @param id 会话 ID
 * @param type 会话类型：0=私聊 1=群聊
 */
data class ConversationInfo(
    val id: String,
    val type: Int
)
