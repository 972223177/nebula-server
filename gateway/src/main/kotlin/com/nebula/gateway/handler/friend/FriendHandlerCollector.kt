package com.nebula.gateway.handler.friend

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.di.register

/**
 * 好友关系 Handler 收集器 — 注册 Friend 模块的所有 Handler（Phase 8）。
 */
class FriendHandlerCollector(
    private val friendRejectHandler: FriendRejectHandler,
    private val friendRequestsHandler: FriendRequestsHandler,
    private val friendListHandler: FriendListHandler,
    private val friendDeleteHandler: FriendDeleteHandler,
    private val friendAddHandler: FriendAddHandler,
    private val friendAcceptHandler: FriendAcceptHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(friendRejectHandler)
        registry.register(friendRequestsHandler)
        registry.register(friendListHandler)
        registry.register(friendDeleteHandler)
        registry.register(friendAddHandler)
        registry.register(friendAcceptHandler)
    }
}
