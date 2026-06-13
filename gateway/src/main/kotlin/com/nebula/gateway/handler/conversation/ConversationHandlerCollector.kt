package com.nebula.gateway.handler.conversation

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.di.register

/**
 * 群组会话 Handler 收集器 — 注册 Conversation 模块的所有 Handler（Phase 7）。
 */
class ConversationHandlerCollector(
    private val listConversationsHandler: ListConversationsHandler,
    private val groupMembersHandler: GroupMembersHandler,
    private val editGroupHandler: EditGroupHandler,
    private val createGroupHandler: CreateGroupHandler,
    private val inviteMemberHandler: InviteMemberHandler,
    private val leaveGroupHandler: LeaveGroupHandler,
    private val kickMemberHandler: KickMemberHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(listConversationsHandler)
        registry.register(groupMembersHandler)
        registry.register(editGroupHandler)
        registry.register(createGroupHandler)
        registry.register(inviteMemberHandler)
        registry.register(leaveGroupHandler)
        registry.register(kickMemberHandler)
    }
}
