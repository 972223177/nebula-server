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
 * Phase 7: Conversation（D-19 事务 + 锁 + 7 个 Handler）
 */
val conversationHandlerModule = module {
    single { ConversationLockManager() }
    single { ListConversationsHandler(get(), get()) }       // ConvRepo + ConvMemberRepo
    single { GroupMembersHandler(get(), get()) }            // ConvMemberRepo + UserRepo
    single { EditGroupHandler(get(), get(), get()) }        // ConvRepo + ConvMemberRepo + PushService
    single { CreateGroupHandler(get(), get(), get(), get(), get()) } // ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService
    single { InviteMemberHandler(get(), get(), get(), get(), get()) } // ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService
    single { LeaveGroupHandler(get(), get(), get(), get(), get()) }  // ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService
    single { KickMemberHandler(get(), get(), get(), get(), get()) }  // ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService

    // HandlerCollector 注册
    single<HandlerCollector> { ConversationHandlerCollector(
        get(), get(), get(), get(), get(), get(), get()
    ) }
}
