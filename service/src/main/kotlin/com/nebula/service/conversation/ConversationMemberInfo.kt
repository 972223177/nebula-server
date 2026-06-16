package com.nebula.service.conversation

/**
 * 会话成员信息 DTO — 供 gateway 层查询会话成员，替代 [com.nebula.repository.entity.ConversationMemberEntity]。
 *
 * 屏蔽 repository 层的 JPA 实体细节，仅暴露 gateway 层需要的业务字段。
 *
 * @param userId 用户 ID
 * @param role 成员角色（owner/member）
 */
data class ConversationMemberInfo(
    val userId: Long,
    val role: String
)
