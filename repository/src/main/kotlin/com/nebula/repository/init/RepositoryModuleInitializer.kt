package com.nebula.repository.init

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.DataSourceProvider
import com.nebula.common.init.ModuleInitializer
import com.nebula.repository.config.JpaConfig
import com.nebula.repository.config.RedisConfig
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.redis.SessionRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.repository.repository.impl.MessageRepositoryImpl
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext

/**
 * Repository 模块初始化器。
 *
 * 依赖 [CommonModuleInitializer] 提供的 [DataSourceProvider] 和 [ApplicationConfig]。
 * 负责初始化持久化层（JPA + Flyway + Redis），创建所有 Repository 实例并注册到 Koin 容器。
 */
class RepositoryModuleInitializer : ModuleInitializer, KoinComponent {

    override val name = "repository"
    override val dependencies = listOf("common")

    override fun init() {
        val dataSourceProvider = get<DataSourceProvider>()
        val config = get<ApplicationConfig>()
        val koin = GlobalContext.get()

        // 初始化 JPA + Flyway
        val jpaConfig = JpaConfig(dataSourceProvider)

        // 初始化 Redis 客户端
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

        // 初始化 Redis Repository
        val sessionRepo = SessionRepository(redisConfig.connection)
        val messageQueueRepo = MessageQueueRepository(redisConfig.messageQueueConnection)
        val onlineStatusRepo = OnlineStatusRepository(redisConfig.connection)

        // 确保 Redis Stream 消费者组就绪
        runBlocking { redisConfig.initializeRedisInfra(messageQueueRepo) }

        // 消息写入路径：Redis Stream → 异步批量刷入 MySQL
        val messageWriteRepo = MessageRepositoryImpl(
            messageQueue = messageQueueRepo,
            jpaMessageRepo = messageRepo,
            emf = jpaConfig.entityManagerFactory
        )
        messageWriteRepo.startFlushTimer()

        // 注册所有产物到 Koin 容器
        koin.declare<EntityManagerFactory>(jpaConfig.entityManagerFactory)
        koin.declare(jpaConfig.transactionTemplate())
        koin.declare<UserRepository>(userRepo)
        koin.declare<SessionRepository>(sessionRepo)
        koin.declare<OnlineStatusRepository>(onlineStatusRepo)
        koin.declare<ConversationRepository>(conversationRepo)
        koin.declare<ConversationMemberRepository>(conversationMemberRepo)
        koin.declare<MessageRepository>(messageRepo)
        koin.declare<MessageRepositoryImpl>(messageWriteRepo)
        koin.declare<MessageQueueRepository>(messageQueueRepo)
        koin.declare<FriendshipRepository>(friendshipRepo)
        koin.declare<FriendRequestRepository>(friendRequestRepo)
        koin.declare<PrivacyRepository>(PrivacyRepository(redisConfig.connection, userRepo))
        koin.declare<StatefulRedisConnection<String, String>>(redisConfig.connection)
    }
}
