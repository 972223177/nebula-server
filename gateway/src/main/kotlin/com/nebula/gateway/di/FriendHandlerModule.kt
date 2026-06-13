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
 * Phase 8: Friend（6 个 Handler）
 */
val friendHandlerModule = module {
    single { FriendRejectHandler(get()) }                                               // FriendRequestRepository
    single { FriendRequestsHandler(get(), get()) }                                      // FriendRequestRepository + UserRepository
    single { FriendListHandler(get(), get(), get(), get()) }                            // FriendshipRepo + UserRepo + OnlineStatusRepo + PrivacyRepo
    single { FriendDeleteHandler(get()) }                                               // FriendshipRepository
    single { FriendAddHandler(get(), get(), get(), get(), get(), get(), get()) }        // FriendRequestRepo + FriendshipRepo + ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService
    single { FriendAcceptHandler(get(), get(), get(), get(), get(), get(), get()) }     // FriendRequestRepo + FriendshipRepo + ConvRepo + ConvMemberRepo + LockManager + TxTemplate + PushService

    // HandlerCollector 注册
    single<HandlerCollector> { FriendHandlerCollector(
        get(), get(), get(), get(), get(), get()
    ) }
}
