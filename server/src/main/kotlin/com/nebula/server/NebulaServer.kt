package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.init.ModuleInitializer
import com.nebula.common.init.commonInitModule
import com.nebula.common.init.topologicalSort
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.di.gatewayModules
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.interceptor.Interceptor
import com.nebula.gateway.push.PushService
import com.nebula.gateway.service.ChatService
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.init.repositoryInitModule
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer
import com.nebula.service.admin.DeadLetterService
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Nebula 服务端应用入口。
 *
 * 启动顺序遵循 D-18 设计决策：日志配置必须早于所有其他初始化步骤，
 * 以确保 ConfigLoader 加载过程中的任何日志输出都能被正确捕获。
 *
 * 启动顺序:
 * 1. 设置 logback 配置文件（必须在其他初始化之前，D-18）
 * 2. 通过 ConfigLoader 加载 HOCON 配置
 * 3. 启动 Koin 容器，注册 ApplicationConfig + 各模块 ModuleInitializer + Gateway 模块定义
 * 4. 通过 ModuleInitializer 模式发现所有模块初始化器，按依赖拓扑排序后依次执行
 * 5. 通过 HandlerCollector 模式统一注册所有 Handler 到 Registry
 * 6. 构造 ChatService，启动 gRPC ChatServer
 * 7. 阻塞在 awaitTermination() 上，等待进程关闭信号
 */
fun main() {
    val env = System.getenv("ENV") ?: "dev"

    // D-18: logback 配置必须在 ConfigLoader 之前设置，否则加载配置期间的日志将丢失
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    // Step 2: 加载配置 — 使用 Typesafe Config 解析 HOCON 文件
    val config: ApplicationConfig = ConfigLoader.load()

    // Step 3: 启动 Koin 容器
    // 先注册 ApplicationConfig 和 ModuleInitializer 实现，再注册 Gateway 模块定义
    // Gateway 模块的 single 定义先注册但不立即实例化，等初始化器执行完后再 get() 可正确解析依赖
    startKoin {
        modules(
            module { single { config } },
            commonInitModule,
            repositoryInitModule,
            *gatewayModules.toTypedArray()
        )
    }

    val koin = GlobalContext.get()

    // Step 4: 通过 ModuleInitializer 模式初始化各模块
    // 发现所有 ModuleInitializer 实现，按依赖拓扑排序后依次执行
    val initializers: List<ModuleInitializer> = koin.getAll()
    initializers.topologicalSort().forEach { it.init() }

    // 确保所有 eager singles 实例化
    koin.createEagerInstances()

    // Step 5: 通过 HandlerCollector 模式统一注册所有 Handler 到 HandlerRegistry
    val registry = koin.get<HandlerRegistry>()
    val codec = koin.get<ProtoCodec>()
    val collectors: List<HandlerCollector> = koin.getAll()
    collectors.forEach { it.registerAll(registry) }

    // Step 6: 构造 ChatService 依赖
    val sessionRegistry = koin.get<SessionRegistry>()
    val interceptors: List<Interceptor> = koin.getAll()
    val dispatcher = Dispatcher(registry, interceptors, codec)

    val userStreamRegistry = UserStreamRegistry()
    val pushSvc = koin.get<PushService>()
    val privacyRepo = koin.get<PrivacyRepository>()
    val onlineStatusRepo = koin.get<OnlineStatusRepository>()
    val friendshipRepo = koin.get<FriendshipRepository>()
    val deadLetterService = koin.get<DeadLetterService>()
    val chatService = ChatService(
        dispatcher, sessionRegistry, registry, userStreamRegistry,
        onlineStatusRepo, friendshipRepo, pushSvc, privacyRepo,
        deadLetterService
    )

    // Step 7: 启动 gRPC 服务
    val chatServer = ChatServer(config)
    chatServer.start(chatService)

    // Step 8: 阻塞等待关闭
    chatServer.blockUntilShutdown()
}
