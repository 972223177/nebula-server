package com.nebula.gateway.di

import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.friend.*
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 好友 Handler Koin 模块 — 注册 Friend 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层 + gateway 组件（推送、锁）。
 * 事务由 Service 层内部通过 JpaTxRunner 管理。
 */
val friendHandlerModule = module {
    single { FriendRejectHandler(get()) }                            // FriendService
    single { FriendRequestsHandler(get()) }                          // FriendService
    single { FriendListHandler(get()) }                              // FriendService
    single { FriendDeleteHandler(get()) }                            // FriendService
    // FriendAdd/FriendAccept 保留 lockManager 依赖（占位），事务由 Service 管理
    single { FriendAddHandler(get(), get(), get(), get()) }           // SensitiveWordService + FriendService + PushService + LockManager
    single { FriendAcceptHandler(get(), get(), get()) }              // FriendService + PushService + LockManager
    single { FriendCheckRelationHandler(get()) }                     // FriendService
    single { FriendBatchCheckRelationHandler(get()) }                 // FriendService

    // HandlerCollector 注册
    single<HandlerCollector>(named("friend")) { FriendHandlerCollector(
        get(), get(), get(), get(), get(), get(), get(), get()
    ) }
}
