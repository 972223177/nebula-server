package com.nebula.repository.init

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.DataSourceProvider
import com.nebula.common.init.ModuleInitializer
import com.nebula.common.session.SessionStore
import com.nebula.repository.config.JpaConfig
import com.nebula.repository.config.RedisConfig
import com.nebula.repository.dao.ConversationDao
import com.nebula.repository.dao.ConversationMemberDao
import com.nebula.repository.dao.DeadLetterDao
import com.nebula.repository.dao.FriendRequestDao
import com.nebula.repository.dao.FriendshipDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.MessageDao
import com.nebula.repository.dao.UserDao
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.redis.SessionRepository
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
 * 负责初始化持久化层（纯 Hibernate + Flyway + Redis），创建所有 DAO 实例
 * 及事务运行器 [JpaTxRunner] 并注册到 Koin 容器。
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
        val emf = jpaConfig.entityManagerFactory

        // 创建事务运行器 + 全部 DAO
        val txRunner = JpaTxRunner(emf)
        val userDao = UserDao()
        val conversationDao = ConversationDao()
        val conversationMemberDao = ConversationMemberDao()
        val messageDao = MessageDao()
        val friendshipDao = FriendshipDao()
        val friendRequestDao = FriendRequestDao()
        val deadLetterDao = DeadLetterDao()

        // 初始化 Redis 客户端（D-77: TLS 和密码由配置注入）
        val redisConfig = RedisConfig(
            host = config.redis.host,
            port = config.redis.port,
            password = config.redis.password,
            ssl = config.redis.ssl
        )

        // 初始化 Redis Repository
        val sessionRepo = SessionRepository(redisConfig.connection)
        val messageQueueRepo = MessageQueueRepository(redisConfig.messageQueueConnection)
        val onlineStatusRepo = OnlineStatusRepository(redisConfig.connection)

        // 确保 Redis Stream 消费者组就绪
        runBlocking { redisConfig.initializeRedisInfra(messageQueueRepo) }

        // 消息写入路径：Redis Stream → 异步批量刷入 MySQL
        val messageWriteRepo = MessageRepositoryImpl(
            messageQueue = messageQueueRepo,
            jpaTxRunner = txRunner,
            emf = emf
        )
        messageWriteRepo.startFlushTimer()

        // 注册所有产物到 Koin 容器
        koin.declare<EntityManagerFactory>(emf)
        koin.declare<JpaTxRunner>(txRunner)
        koin.declare<UserDao>(userDao)
        koin.declare<ConversationDao>(conversationDao)
        koin.declare<ConversationMemberDao>(conversationMemberDao)
        koin.declare<MessageDao>(messageDao)
        koin.declare<FriendshipDao>(friendshipDao)
        koin.declare<FriendRequestDao>(friendRequestDao)
        koin.declare<DeadLetterDao>(deadLetterDao)
        koin.declare<SessionRepository>(sessionRepo)
        koin.declare<SessionStore>(sessionRepo)
        koin.declare<OnlineStatusRepository>(onlineStatusRepo)
        koin.declare<MessageRepositoryImpl>(messageWriteRepo)
        koin.declare<MessageQueueRepository>(messageQueueRepo)
        koin.declare<PrivacyRepository>(PrivacyRepository(redisConfig.connection, userDao, txRunner))
        koin.declare<StatefulRedisConnection<String, String>>(redisConfig.connection)
    }
}
