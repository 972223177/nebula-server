package com.nebula.gateway.di

/**
 * Gateway DI 模块聚合入口。
 *
 * 将分散在各个子模块中的 Koin 定义统一聚合，方便 NebulaServer 统一导入。
 * 各子模块按业务分类拆分，便于维护和查找：
 *
 * - [frameworkModule]: 框架级基础设施（HandlerRegistry、ProtoCodec、SessionRegistry、拦截器）
 * - [userHandlerModule]: 用户业务 Handler（Login、Register、SearchUser 等）
 * - [chatHandlerModule]: 聊天和消息 Handler（SendMessage、PullMessages、ReadReport）
 * - [conversationHandlerModule]: 会话 Handler（CreateGroup、InviteMember 等）
 * - [friendHandlerModule]: 好友 Handler（FriendAdd、FriendAccept 等）
 */
val gatewayModules = listOf(
    frameworkModule,
    userHandlerModule,
    chatHandlerModule,
    conversationHandlerModule,
    friendHandlerModule
)
