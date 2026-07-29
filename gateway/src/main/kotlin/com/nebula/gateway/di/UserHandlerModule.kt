package com.nebula.gateway.di

import com.nebula.gateway.handler.user.*
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 用户业务 Handler Koin 模块 — 注册 User 相关的 Handler 和组件。
 *
 * Phase 5: 包含 PingHandler + 9 个用户业务 Handler（login/logout/register/search/getProfile/batchGet/batchGetStatus/setPrivacy/getPrivacy）
 * Handler 依赖 Service 层而非直接依赖 Repository。
 */
val userHandlerModule = module {
    // Phase 5: User Handler — 依赖 Service 层
    single { LoginHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class                     // UserService + SessionRegistry
    single { LogoutHandler(get()) } bind com.nebula.gateway.handler.Handler::class                            // SessionRegistry（AUTH-06 主动下线）
    single { RegisterHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class                   // SensitiveWordService + UserService
    single { SearchUserHandler(get()) } bind com.nebula.gateway.handler.Handler::class                       // UserService
    single { GetProfileHandler(get()) } bind com.nebula.gateway.handler.Handler::class                       // UserService
    single { BatchGetUserHandler(get()) } bind com.nebula.gateway.handler.Handler::class                     // UserService
    single { BatchGetStatusHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class            // OnlineStatusService + UserPrivacyService
    single { SetPrivacyHandler(get(), get(), get(), get(), get(named("serverScope"))) } bind com.nebula.gateway.handler.Handler::class   // UserPrivacyService + OnlineStatusService + PushService + FriendService + serverScope
    single { GetPrivacyHandler(get()) } bind com.nebula.gateway.handler.Handler::class                       // UserPrivacyService

}
