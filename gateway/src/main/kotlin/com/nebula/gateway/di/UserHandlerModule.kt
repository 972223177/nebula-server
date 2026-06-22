package com.nebula.gateway.di

import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.handler.user.UserHandlerCollector
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
    single { RegisterHandler(get()) }                         // UserService
    single { SearchUserHandler(get()) }                       // UserService
    single { GetProfileHandler(get()) }                       // UserService
    single { BatchGetUserHandler(get()) }                     // UserService
    single { BatchGetStatusHandler(get(), get()) }            // OnlineStatusService + UserPrivacyService
    single { SetPrivacyHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) }   // UserPrivacyService + OnlineStatusService + PushService + FriendService + sendHandlerScope
    single { GetPrivacyHandler(get()) }                       // UserPrivacyService

    // HandlerCollector 注册
    single<HandlerCollector>(named("user")) { UserHandlerCollector(
        get(), get(), get(), get(), get(), get(), get(), get()
    ) }
}
