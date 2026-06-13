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
import org.koin.dsl.module

/**
 * 用户业务 Handler Koin 模块 — 注册 User 相关的 Handler 和组件。
 *
 * Phase 5: 包含 PingHandler + 8 个用户业务 Handler
 */
val userHandlerModule = module {
    // Phase 5: User Handler
    single { LoginHandler(get(), get()) }                     // UserRepository + SessionRegistry
    single { RegisterHandler(get(), get(), get()) }           // UserRepository + SnowflakeIdGenerator + EntityManagerFactory
    single { SearchUserHandler(get()) }                       // UserRepository
    single { GetProfileHandler(get()) }                       // UserRepository
    single { BatchGetUserHandler(get()) }                     // UserRepository
    single { BatchGetStatusHandler(get(), get()) }            // OnlineStatusRepository + PrivacyRepository
    single { SetPrivacyHandler(get(), get(), get(), get()) }  // PrivacyRepository + OnlineStatusRepository + PushService + FriendshipRepository
    single { GetPrivacyHandler(get()) }                       // PrivacyRepository

    // HandlerCollector 注册
    single<HandlerCollector> { UserHandlerCollector(
        get(), get(), get(), get(), get(), get(), get(), get()
    ) }
}
