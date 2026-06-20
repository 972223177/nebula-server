package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.testutil.TestTags
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
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.persistence.EntityManagerFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Handler 注册测试基类 — 提供所有 Repository 层 mock 和 [buildExternalModule]。
 *
 * 设计决策：
 * - 每个子类仅加载特定领域的 Handler，避免单 JVM 加载全部 50+ Bean（D-32 大型 Koin 容器测试拆分）
 * - mock 字段置为 protected，子类按需引用
 * - 标注 koin-di 标签，由独立 Gradle test task 运行（forkEvery=0，关闭 JVM fork 开销）
 *
 * 方案 A 重构（2026-06-20）：从 Spring Data JpaRepository mock 切换到 DAO mock，
 * 并使用 JpaTxRunner mock 替代 TransactionTemplate。
 */
@Tag(TestTags.KOIN_DI)
abstract class HandlerRegistryTestBase {

    // ===================== Repository 层 Mock =====================

    /** 会话仓库 mock */
    protected val sessionRepo = mockk<SessionRepository>()
    /** 在线状态仓库 mock */
    protected val onlineStatusRepo = mockk<OnlineStatusRepository>()
    /** 雪花 ID 生成器 mock */
    protected val idGenerator = mockk<SnowflakeIdGenerator>()
    /** 隐私设置仓库 mock */
    protected val privacyRepo = mockk<PrivacyRepository>()
    /** Redis 连接 mock（relaxed — StreamObserver 等接口大量未使用方法） */
    protected val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    /** 消息队列仓库 mock */
    protected val messageQueueRepo = mockk<MessageQueueRepository>()
    /** EntityManagerFactory mock */
    protected val emf = mockk<EntityManagerFactory>()
    /** JPA 事务运行器 mock */
    protected val txRunner = mockk<JpaTxRunner>()
    /** UserDao mock */
    protected val userDao = mockk<UserDao>()
    /** ConversationDao mock */
    protected val conversationDao = mockk<ConversationDao>()
    /** ConversationMemberDao mock */
    protected val conversationMemberDao = mockk<ConversationMemberDao>()
    /** MessageDao mock */
    protected val messageDao = mockk<MessageDao>()
    /** FriendshipDao mock */
    protected val friendshipDao = mockk<FriendshipDao>()
    /** FriendRequestDao mock */
    protected val friendRequestDao = mockk<FriendRequestDao>()
    /** DeadLetterDao mock */
    protected val deadLetterDao = mockk<DeadLetterDao>()

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
        single { onlineStatusRepo }
        single { idGenerator }
        single { redisConnection as StatefulRedisConnection<String, String> }
        single { messageQueueRepo }
        single { emf as EntityManagerFactory }
        single { txRunner }
        single { userDao }
        single { conversationDao }
        single { conversationMemberDao }
        single { messageDao }
        single { friendshipDao }
        single { friendRequestDao }
        single { deadLetterDao }
        single { privacyRepo }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        // 取消 CoroutineScope，释放 Dispatchers.IO 线程，避免非守护线程阻止 JVM 退出
        scope.cancel()
    }
}
