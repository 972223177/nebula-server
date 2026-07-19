package com.nebula.gateway.di

import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.user.*
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 用户业务 Handler Koin 模块 — 注册 User 相关的 Handler 和组件。
 *
 * Phase 5: 包含 PingHandler + 8 个用户业务 Handler
 * Handler 依赖 Service 层而非直接依赖 Repository。
 */
val userHandlerModule = module {
    // Phase 5: User Handler — 依赖 Service 层
    single { LoginHandler(get(), get()) }                     // UserService + SessionRegistry
    single { RegisterHandler(get(), get()) }                   // SensitiveWordService + UserService
    single { SearchUserHandler(get()) }                       // UserService
    single { GetProfileHandler(get()) }                       // UserService
    single { BatchGetUserHandler(get()) }                     // UserService
    single { BatchGetStatusHandler(get(), get()) }            // OnlineStatusService + UserPrivacyService
    single { SetPrivacyHandler(get(), get(), get(), get(), get(named("serverScope"))) }   // UserPrivacyService + OnlineStatusService + PushService + FriendService + serverScope
    single { GetPrivacyHandler(get()) }                       // UserPrivacyService

    // HandlerCollector 注册
    single<HandlerCollector>(named("user")) { UserHandlerCollector(
        get(), get(), get(), get(), get(), get(), get(), get()
    ) }
}
