package com.nebula.gateway.di

import com.nebula.gateway.handler.conversation.*
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 会话 Handler Koin 模块 — 注册 Conversation 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层 + gateway 组件（锁、推送）。
 * 事务由 Service 层内部通过 JpaTxRunner 管理。
 */
val conversationHandlerModule = module {
    single { ConversationLockManager() }
    single { ListConversationsHandler(get()) } bind com.nebula.gateway.handler.Handler::class                        // ConversationService
    single { GroupMembersHandler(get()) } bind com.nebula.gateway.handler.Handler::class                             // ConversationService
    single { EditGroupHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class                  // SensitiveWordService + ConversationService + PushService
    // 创建群聊无需会话级锁，Service 内置事务
    single { CreateGroupHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class                       // ConversationService + PushService
    // 邀请/踢人/退群/删除群会话需要会话级锁保护并发
    single { InviteMemberHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class               // ConversationService + LockManager + PushService
    single { LeaveGroupHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class                 // ConversationService + LockManager + PushService
    single { KickMemberHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class                 // ConversationService + LockManager + PushService
    // 删除会话：按 type/role 分支（私聊隐藏 / 群退群 / 群主解散），需要锁 + 推送
    single { DeleteConversationHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class          // ConversationService + LockManager + PushService
    single { CreatePrivateConversationHandler(get()) } bind com.nebula.gateway.handler.Handler::class                // ConversationService
    single { GroupListHandler(get()) } bind com.nebula.gateway.handler.Handler::class                                // ConversationService

}
