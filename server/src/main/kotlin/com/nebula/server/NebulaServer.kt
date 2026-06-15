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
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.impl.MessageRepositoryImpl
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.sequence.SeqService
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.sql.DataSource
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
 * 4. 通过 ModuleInitializer 模式发现所有模块初始化器，按依赖拓扑排序后依次执行（含逆序回滚，CQ-09）
 * 5. 通过 HandlerCollector 模式统一注册所有 Handler 到 Registry
 * 6. 构造 ChatService，启动 gRPC ChatServer
 * 7. 注册 JVM Shutdown Hook（CQ-05）
 * 8. 阻塞在 awaitTermination() 上，等待进程关闭信号
 */
fun main() {
    val logger = KotlinLogging.logger {}
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

    // Step 4: 通过 ModuleInitializer 模式初始化各模块（CQ-09: 含逆序回滚）
    // 发现所有 ModuleInitializer 实现，按依赖拓扑排序后依次执行
    val initializers: List<ModuleInitializer> = koin.getAll()
    val sortedInitializers = initializers.topologicalSort()
    // CQ-09: 追踪已初始化的模块，用于失败时逆序回滚
    val initialized = mutableListOf<ModuleInitializer>()
    try {
        sortedInitializers.forEach { init ->
            init.init()
            initialized.add(init)
        }
    } catch (e: Exception) {
        logger.error(e) { "模块初始化失败: ${(initialized.lastOrNull()?.name ?: "unknown")}，开始逆序回滚 ${initialized.size} 个已初始化模块" }
        // CQ-09: 逆序回滚已初始化的模块
        initialized.reversed().forEach { init ->
            try {
                init.shutdown()
                logger.info { "已回滚模块: ${init.name}" }
            } catch (t: Throwable) {
                logger.error(t) { "回滚模块 ${init.name} 失败（资源可能已泄露）" }
            }
        }
        throw IllegalStateException("服务器初始化失败: ${e.message}", e)
    }

    // 确保所有 eager singles 实例化
    koin.createEagerInstances()

    // D-81/H21: 从 MySQL 恢复 Redis 序列号
    // SeqService 序列号基于 Redis INCR，若 Redis 重启会丢失所有序列号。
    // 启动时从 MySQL 消息计数恢复：nextSeq = COUNT(*) + 1，使用 SETNX 避免覆盖已有 Key。
    runBlocking {
        val seqService = koin.get<SeqService>()
        val convRepo = koin.get<ConversationRepository>()
        val msgRepo = koin.get<MessageRepository>()
        val memberRepo = koin.get<ConversationMemberRepository>()

        logger.info { "开始从 MySQL 恢复 Redis 序列号..." }
        var restoredCount = 0

        val conversations = withContext(Dispatchers.IO) {
            convRepo.findAll()
        }

        conversations.forEach { conv ->
            val msgCount = withContext(Dispatchers.IO) {
                msgRepo.countByConversationId(conv.id)
            }
            val nextSeq = msgCount + 1L

            val members = withContext(Dispatchers.IO) {
                memberRepo.findByConversationId(conv.id)
            }

            members.forEach { member ->
                val restored = seqService.tryRestoreSeq(conv.id, member.userId, nextSeq)
                if (restored) restoredCount++
            }
        }

        logger.info { "序列号恢复完成: $restoredCount 个 Key 已初始化（共 ${conversations.size} 个会话）" }
    }

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

    // Step 8: 注册 JVM Shutdown Hook（CQ-05）
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

        // 2. 停止消息异步写入刷盘定时器
        try {
            koin.get<MessageRepositoryImpl>().stop()
            logger.info { "消息异步写入已停止" }
        } catch (e: Exception) {
            logger.error(e) { "停止消息异步写入失败" }
        }

        // 3. 关闭 Redis 连接
        try {
            val redisConn = koin.get<StatefulRedisConnection<String, String>>()
            redisConn.close()
            logger.info { "Redis 连接已关闭" }
        } catch (e: Exception) {
            logger.error(e) { "关闭 Redis 连接失败" }
        }

        // 4. 关闭数据库连接池
        try {
            val ds = koin.get<DataSource>()
            (ds as? HikariDataSource)?.close()
            logger.info { "数据库连接池已关闭" }
        } catch (e: Exception) {
            logger.error(e) { "关闭数据库连接池失败" }
        }

        logger.info { "优雅关闭完成" }
    })

    // Step 9: 阻塞等待关闭
    chatServer.blockUntilShutdown()
}
