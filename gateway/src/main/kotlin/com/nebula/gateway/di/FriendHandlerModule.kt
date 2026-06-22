package com.nebula.gateway.di

import com.nebula.gateway.handler.friend.FriendAcceptHandler
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.gateway.handler.friend.FriendDeleteHandler
import com.nebula.gateway.handler.friend.FriendListHandler
import com.nebula.gateway.handler.friend.FriendRejectHandler
import com.nebula.gateway.handler.friend.FriendRequestsHandler
import com.nebula.gateway.handler.friend.FriendHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
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
    single { FriendAddHandler(get(), get(), get()) }                 // FriendService + PushService + LockManager
    single { FriendAcceptHandler(get(), get(), get()) }              // FriendService + PushService + LockManager

    // HandlerCollector 注册
    single<HandlerCollector>(named("friend")) { FriendHandlerCollector(
        get(), get(), get(), get(), get(), get()
    ) }
}
