package com.nebula.gateway.di

import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.friend.FriendService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import org.koin.dsl.module

/**
 * Service 层 Koin 模块 — 注册所有业务服务实例。
 *
 * Service 层依赖 Repository 层，不依赖网关层组件。
 * 所有 Service 实例在此注册，供 Handler 通过 Koin 注入。
 */
val serviceModule = module {
    single { UserService(get(), get(), get()) }
    single { UserPrivacyService(get(), get(), get()) }
    single { MessageService(get(), get(), get(), get(), get(), get(), get()) }
    single { ConversationService(get(), get(), get()) }
    single { FriendService(get(), get(), get(), get(), get(), get(), get()) }
}
