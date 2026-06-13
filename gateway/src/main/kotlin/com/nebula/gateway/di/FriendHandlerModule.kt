package com.nebula.gateway.di

import com.nebula.gateway.handler.friend.FriendAcceptHandler
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.gateway.handler.friend.FriendDeleteHandler
import com.nebula.gateway.handler.friend.FriendListHandler
import com.nebula.gateway.handler.friend.FriendRejectHandler
import com.nebula.gateway.handler.friend.FriendRequestsHandler
import com.nebula.gateway.handler.friend.FriendHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
import org.koin.dsl.module

/**
 * 好友 Handler Koin 模块 — 注册 Friend 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层 + gateway 组件（锁、事务、推送）。
 */
val friendHandlerModule = module {
    single { FriendRejectHandler(get()) }                                               // FriendService
    single { FriendRequestsHandler(get()) }                                             // FriendService
    single { FriendListHandler(get()) }                                                 // FriendService
    single { FriendDeleteHandler(get()) }                                               // FriendService
    single { FriendAddHandler(get(), get(), get()) }                                    // FriendService + PushService + LockManager
    single { FriendAcceptHandler(get(), get(), get()) }                                 // FriendService + PushService + LockManager

    // HandlerCollector 注册
    single<HandlerCollector> { FriendHandlerCollector(
        get(), get(), get(), get(), get(), get()
    ) }
}
