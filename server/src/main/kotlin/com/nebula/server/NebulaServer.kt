package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.gateway.bootstrap.ServerBootstrap
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.service.ChatService
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Nebula 服务端应用入口。
 *
 * 启动顺序遵循 D-18 设计决策：日志配置必须早于所有其他初始化步骤，
 * 以确保 ConfigLoader 加载过程中的任何日志输出都能被正确捕获。
 *
 * 分层架构: common ←── repository ←── service ←── gateway ←── server
 * server 层仅依赖 gateway 模块，所有跨层操作（repository/service 访问）通过
 * [ServerBootstrap] 封装在 gateway 层内执行。
 *
 * 启动顺序:
 * 1. 设置 logback 配置文件（必须在其他初始化之前，D-18）
 * 2. 通过 ConfigLoader 加载 HOCON 配置
 * 3. 启动 Koin 容器，注册 ApplicationConfig + ServerBootstrap 提供的所有模块
 * 4. ServerBootstrap 执行模块初始化（ModuleInitializer 发现/排序/执行/回滚）
 * 5. ServerBootstrap 执行序列号恢复（D-81/H21）
 * 6. ServerBootstrap 执行死信桥接注册（M11）
 * 7. 通过 HandlerCollector 模式统一注册所有 Handler 到 Registry
 * 8. ServerBootstrap 构造 ChatService
 * 9. 启动 gRPC ChatServer
 * 10. 注册 JVM Shutdown Hook（CQ-05），ServerBootstrap 处理 repository 层关闭
 * 11. 阻塞在 awaitTermination() 上，等待进程关闭信号
 */
fun main() {
    val logger = KotlinLogging.logger {}
    val env = System.getenv("ENV") ?: "dev"

    // D-18: logback 配置必须在 ConfigLoader 之前设置，否则加载配置期间的日志将丢失
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    // Step 2: 加载配置 — 使用 Typesafe Config 解析 HOCON 文件
    val config: ApplicationConfig = ConfigLoader.load()

    // Step 3: 启动 Koin 容器
    // ServerBootstrap 聚合了所有层的 Koin 模块定义，server 无需直接 import 下层模块
    startKoin {
        modules(
            module { single { config } },
            *ServerBootstrap.koinModules.toTypedArray()
        )
    }

    val koin = GlobalContext.get()

    // Step 4: 通过 ServerBootstrap 执行模块初始化（CQ-09: 含逆序回滚）
    // 发现所有 ModuleInitializer 实现，按依赖拓扑排序后依次执行
    ServerBootstrap.initializeModules(koin)

    // Step 5: 从 MySQL 恢复 Redis 序列号（D-81/H21）
    ServerBootstrap.recoverSequences(koin)

    // Step 6: 注入死信创建回调（M11）
    ServerBootstrap.setupDeadLetterBridge(koin)

    // Step 7: 通过 HandlerCollector 模式统一注册所有 Handler 到 HandlerRegistry
    val registry = koin.get<HandlerRegistry>()
    val codec = koin.get<ProtoCodec>()
    val collectors: List<HandlerCollector> = koin.getAll()
    collectors.forEach { it.registerAll(registry) }

    // Step 8: ServerBootstrap 构造 ChatService（封装 repository/service 依赖）
    val chatService: ChatService = ServerBootstrap.createChatService(koin)

    // Step 9: 启动 gRPC 服务
    val chatServer = ChatServer(config)
    chatServer.start(chatService)

    // Step 10: 注册 JVM Shutdown Hook（CQ-05）
    // 逆序关闭资源：gRPC → 消息写入 → Redis → 数据库连接池
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "收到关闭信号，开始优雅关闭..." }

        // 1. 停止 gRPC 服务（不再接受新连接）
        try {
            chatServer.stop()
            logger.info { "gRPC 服务已停止" }
        } catch (e: Exception) {
            logger.error(e) { "停止 gRPC 服务失败" }
        }

        // 2-4. 停止消息刷盘、关闭 Redis、关闭数据库连接池（由 ServerBootstrap 处理）
        ServerBootstrap.executeShutdown(koin)

        logger.info { "优雅关闭完成" }
    })

    // Step 11: 阻塞等待关闭
    chatServer.blockUntilShutdown()
}
