package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.testutil.TestTags
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
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.persistence.EntityManagerFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.springframework.transaction.support.TransactionTemplate

/**
 * Handler 注册测试基类 — 提供所有 Repository 层 mock 和 [buildExternalModule]。
 *
 * 设计决策：
 * - 每个子类仅加载特定领域的 Handler，避免单 JVM 加载全部 50+ Bean（D-32 大型 Koin 容器测试拆分）
 * - mock 字段置为 protected，子类按需引用
 * - 标注 koin-di 标签，由独立 Gradle test task 运行（forkEvery=0，关闭 JVM fork 开销）
 */
@Tag(TestTags.KOIN_DI)
abstract class HandlerRegistryTestBase {

    // ===================== Repository 层 Mock =====================

    /** 会话仓库 mock */
    protected val sessionRepo = mockk<SessionRepository>()
    /** 用户仓库 mock */
    protected val userRepo = mockk<UserRepository>()
    /** 在线状态仓库 mock */
    protected val onlineStatusRepo = mockk<OnlineStatusRepository>()
    /** 雪花 ID 生成器 mock */
    protected val idGenerator = mockk<SnowflakeIdGenerator>()
    /** 隐私设置仓库 mock */
    protected val privacyRepo = mockk<PrivacyRepository>()
    /** Redis 连接 mock（relaxed — StreamObserver 等接口大量未使用方法） */
    protected val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    /** 会话成员仓库 mock */
    protected val conversationMemberRepo = mockk<ConversationMemberRepository>()
    /** 会话仓库 mock */
    protected val conversationRepo = mockk<ConversationRepository>()
    /** 消息仓库 mock */
    protected val messageRepo = mockk<MessageRepository>()
    /** 消息队列仓库 mock */
    protected val messageQueueRepo = mockk<MessageQueueRepository>()
    /** 好友关系仓库 mock */
    protected val friendshipRepo = mockk<FriendshipRepository>()
    /** 好友请求仓库 mock */
    protected val friendRequestRepo = mockk<FriendRequestRepository>()
    /** EntityManagerFactory mock */
    protected val emf = mockk<EntityManagerFactory>()
    /** Spring 事务模板 mock */
    protected val transactionTemplate = mockk<TransactionTemplate>()

    // ===================== 基础设施 Mock =====================

    /** 协程作用域 */
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===================== 共享 Koin 模块 =====================

    /**
     * 构建外部依赖 Koin 模块 — 注册所有 Repository 层 mock 到 Koin 容器。
     *
     * 子类只需在 startKoin 中组合此模块 + frameworkModule + 领域 Handler 模块。
     */
    protected fun buildExternalModule() = module {
        single { sessionRepo }
        single { userRepo }
        single { onlineStatusRepo }
        single { idGenerator }
        single { redisConnection as StatefulRedisConnection<String, String> }
        single { messageQueueRepo }
        single { emf as EntityManagerFactory }
        single { conversationMemberRepo }
        single { conversationRepo }
        single { messageRepo }
        single { friendshipRepo }
        single { friendRequestRepo }
        single { privacyRepo }
        single { transactionTemplate }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }
}
