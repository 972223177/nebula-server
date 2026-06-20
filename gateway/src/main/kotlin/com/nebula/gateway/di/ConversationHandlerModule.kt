package com.nebula.gateway.di

import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.handler.conversation.ConversationHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
import org.koin.dsl.module

/**
 * 会话 Handler Koin 模块 — 注册 Conversation 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层 + gateway 组件（锁、推送）。
 * 事务由 Service 层内部通过 JpaTxRunner 管理。
 */
val conversationHandlerModule = module {
    single { ConversationLockManager() }
    single { ListConversationsHandler(get()) }                        // ConversationService
    single { GroupMembersHandler(get()) }                             // ConversationService
    single { EditGroupHandler(get(), get()) }                         // ConversationService + PushService
    // 创建群聊无需会话级锁，Service 内置事务
    single { CreateGroupHandler(get(), get()) }                       // ConversationService + PushService
    // 邀请/踢人/退群需要会话级锁保护并发
    single { InviteMemberHandler(get(), get(), get()) }               // ConversationService + LockManager + PushService
    single { LeaveGroupHandler(get(), get(), get()) }                 // ConversationService + LockManager + PushService
    single { KickMemberHandler(get(), get(), get()) }                 // ConversationService + LockManager + PushService

    // HandlerCollector 注册
    single<HandlerCollector> { ConversationHandlerCollector(
        get(), get(), get(), get(), get(), get(), get()
    ) }
}
