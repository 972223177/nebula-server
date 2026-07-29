package com.nebula.gateway.di

import com.nebula.gateway.handler.AllHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.service.init.serviceKoinModule
import org.koin.dsl.module

/**
 * 统一 Handler 收集器模块 — 声明唯一的 [AllHandlerCollector]，经 Koin `getAll<Handler<*,*>>()`
 * 自动发现并注册全部 Handler，替代原先按分组逐个列举 Handler 的多个 Collector 实现
 * （UserHandlerCollector / ChatHandlerCollector / ...）。
 *
 * 新增 Handler 只需在对应 *HandlerModule 中声明 `single { XHandler(...) }`，无需改动任何 Collector；
 * [com.nebula.server.NebulaServer] 仍通过 `koin.getAll<HandlerCollector>()` 统一注册，启动契约不变。
 */
val handlerCollectorModule = module {
    single<HandlerCollector> { AllHandlerCollector(getAll()) }
}

/**
 * Gateway DI 模块聚合入口。
 *
 * 将分散在各个子模块中的 Koin 定义统一聚合，方便 NebulaServer 统一导入。
 * 各子模块按业务分类拆分，便于维护和查找：
 *
 * - [serviceKoinModule]: 业务服务层（UserService、MessageService 等）— 位于 service 模块
 * - [frameworkModule]: 框架级基础设施（HandlerRegistry、ProtoCodec、SessionRegistry、拦截器）
 * - [userHandlerModule]: 用户业务 Handler（Login、Register、SearchUser 等）
 * - [chatHandlerModule]: 聊天和消息 Handler（SendMessage、PullMessages、ReadReport）
 * - [conversationHandlerModule]: 会话 Handler（CreateGroup、InviteMember 等）
 * - [friendHandlerModule]: 好友 Handler（FriendAdd、FriendAccept 等）
 * - [messageReliabilityModule]: 消息可靠性（Phase 10）：序列号、投递跟踪、死信及补偿
 * - [externalHandlerModule]: 外部服务 Handler/Collector（天气 / 搜索）
 * - [handlerCollectorModule]: 统一 Handler 收集器（AllHandlerCollector，经 getAll 自动注册全部 Handler）
 */
val gatewayModules = listOf(
    serviceKoinModule,
    frameworkModule,
    userHandlerModule,
    chatHandlerModule,
    conversationHandlerModule,
    friendHandlerModule,
    messageReliabilityModule,
    sensitiveWordHandlerModule,
    externalHandlerModule,       // ← 外部服务 Handler/Collector（天气 / 搜索）
    handlerCollectorModule
)
