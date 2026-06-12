package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.HikariDataSourceProvider
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.config.JpaConfig
import com.nebula.repository.config.RedisConfig
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.SessionRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.repository.repository.impl.MessageRepositoryImpl
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer
import kotlinx.coroutines.runBlocking

/**
 * Nebula 服务端应用入口。
 *
 * 启动顺序遵循 D-18 设计决策：日志配置必须早于所有其他初始化步骤，
 * 以确保 ConfigLoader 加载过程中的任何日志输出都能被正确捕获。
 *
 * 启动顺序:
 * 1. 设置 logback 配置文件（必须在其他初始化之前，D-18）
 * 2. 通过 ConfigLoader 加载 HOCON 配置
 * 3. 初始化 SnowflakeIdGenerator（Phase 5 消息 ID 生成正式使用）
 * 4. 初始化 HikariCP 数据库连接池（Phase 3 数据持久化正式使用）
 * 5. 启动 gRPC ChatServer
 * 6. 阻塞在 awaitTermination() 上，等待进程关闭信号
 */
fun main() {
    val env = System.getenv("ENV") ?: "dev"

    // D-18: logback 配置必须在 ConfigLoader 之前设置，否则加载配置期间的日志将丢失
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    // Step 2: 加载配置 — 使用 Typesafe Config 解析 HOCON 文件
    val config: ApplicationConfig = ConfigLoader.load()

    // Step 3: 初始化 Snowflake ID 生成器 — Phase 5 正式使用
    // 雪花算法生成 64 位唯一 ID，位分配: 41 位时间戳 | 10 位 Worker ID | 12 位序列号
    val idGenerator = SnowflakeIdGenerator(
        workerId = config.snowflake.workerId,
        epoch = config.snowflake.epoch
    )

    // Step 4: 初始化数据库连接池 — Phase 3 正式使用
    // HikariCP 连接池通过 HikariDataSourceProvider 封装，屏蔽直接依赖
    val dataSourceProvider = HikariDataSourceProvider(config.database)

    // Step 4.5: Phase 3 — 初始化持久化层
    // JPA + Flyway：先执行迁移再创建 EntityManagerFactory
    val jpaConfig = JpaConfig(dataSourceProvider)
    // Redis 客户端：Lettuce 共享连接
    val redisConfig = RedisConfig(
        host = config.redis.host,
        port = config.redis.port
    )
    // 获取各 JPA Repository 代理
    val userRepo = jpaConfig.getRepository(UserRepository::class.java)
    val conversationRepo = jpaConfig.getRepository(ConversationRepository::class.java)
    val conversationMemberRepo = jpaConfig.getRepository(ConversationMemberRepository::class.java)
    val messageRepo = jpaConfig.getRepository(MessageRepository::class.java)
    val friendshipRepo = jpaConfig.getRepository(FriendshipRepository::class.java)
    val friendRequestRepo = jpaConfig.getRepository(FriendRequestRepository::class.java)
    // Redis Repository 初始化
    val sessionRepo = SessionRepository(redisConfig.connection)
    val messageQueueRepo = MessageQueueRepository(redisConfig.connection)
    val onlineStatusRepo = OnlineStatusRepository(redisConfig.connection)
    // 确保 Redis Stream 消费者组就绪（runBlocking 用于 main 线程非阻塞上下文的桥接）
    runBlocking { redisConfig.initializeRedisInfra(messageQueueRepo) }
    // 消息写入路径：Redis Stream → 异步批量刷入 MySQL
    val messageWriteRepo = MessageRepositoryImpl(
        messageQueue = messageQueueRepo,
        jpaMessageRepo = messageRepo,
        emf = jpaConfig.entityManagerFactory
    )
    messageWriteRepo.startFlushTimer()

    // Step 5: 启动 gRPC 服务 — 包含 SSL/TLS、keepalive、流控配置
    val chatServer = ChatServer(config)
    chatServer.start()

    // Step 6: 阻塞等待关闭 — JVM 进程在此等待，直到收到 SIGTERM/Shutdown 信号
    chatServer.blockUntilShutdown()
}
