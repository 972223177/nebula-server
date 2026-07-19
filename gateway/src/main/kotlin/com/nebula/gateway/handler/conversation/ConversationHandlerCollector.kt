package com.nebula.gateway.handler.conversation

import com.nebula.gateway.di.register
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * 群组会话 Handler 收集器 — 注册 Conversation 模块的所有 Handler（Phase 7）。
 */
class ConversationHandlerCollector(
    private val listConversationsHandler: ListConversationsHandler,
    private val groupMembersHandler: GroupMembersHandler,
    private val groupListHandler: GroupListHandler,
    private val editGroupHandler: EditGroupHandler,
    private val createGroupHandler: CreateGroupHandler,
    private val inviteMemberHandler: InviteMemberHandler,
    private val leaveGroupHandler: LeaveGroupHandler,
    private val kickMemberHandler: KickMemberHandler,
    private val deleteConversationHandler: DeleteConversationHandler,
    private val createPrivateConversationHandler: CreatePrivateConversationHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(listConversationsHandler)
        registry.register(groupMembersHandler)
        registry.register(groupListHandler)
        registry.register(editGroupHandler)
        registry.register(createGroupHandler)
        registry.register(inviteMemberHandler)
        registry.register(leaveGroupHandler)
        registry.register(kickMemberHandler)
        registry.register(deleteConversationHandler)
        registry.register(createPrivateConversationHandler)
    }
}
