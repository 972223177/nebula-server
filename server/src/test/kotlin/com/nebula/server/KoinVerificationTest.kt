package com.nebula.server

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.di.*
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceConfig
import com.nebula.common.external.ExternalServiceQuotaConfig
import com.nebula.common.init.DeadLetterCallback
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.dao.*
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.common.session.SessionStore
import com.nebula.repository.redis.SessionRepository
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationQueryService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.GroupService
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.GeoIpService
import com.nebula.service.external.SearchService
import com.nebula.service.external.WeatherService
import com.nebula.service.friend.FriendRequestService
import com.nebula.service.friend.FriendService
import com.nebula.service.friend.FriendshipService
import com.nebula.service.init.serviceKoinModule
import com.nebula.service.sequence.SeqService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Koin 容器验证测试 — 验证所有 Phase 组件可在 Koin 容器中正确解析（D-01, D-13）。
 *
 * 测试方法：启动 Koin 并加载 gateway 模块 + 外部依赖模块，
 * 然后逐一解析注册组件，确保无 InstanceCreationException。
 *
 * 方案 A 重构（2026-06-20）：Spring Data Repository 替换为 DAO + JpaTxRunner。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KoinVerificationTest {

    /** 构建外部依赖模块 — 使用 mock 对象代替真实基础设施 */
    @Suppress("UNCHECKED_CAST")
    private fun buildMockModule() = module {
        single<UserDao> { mockk() }
        single<SessionRepository> { mockk() }
        single<SessionStore> { mockk() }        // 运行时由 RepositoryModuleInitializer.declare
        single<ConversationDao> { mockk() }
        single<ConversationMemberDao> { mockk() }
        single<MessageDao> { mockk() }
        single<MessageQueueRepository> { mockk() }
        single<RedisStreamQueue> { mockk() }
        single<FriendshipDao> { mockk() }
        single<FriendRequestDao> { mockk() }
        single<DeadLetterDao> { mockk() }
        single<JpaTxRunner> { mockk() }
        single<StatefulRedisConnection<String, String>> { mockk(relaxed = true) }
        single<SensitiveWordService> { mockk(relaxed = true) }
        single<SnowflakeIdGenerator> { mockk() }
        single<OnlineStatusRepository> { OnlineStatusRepository(get()) }
        single<PrivacyRepository> { mockk() }
        // 外部服务配置（由 server 层 ConfigLoader 运行时 declare；此处用 relaxed mock 避免构造访问嵌套字段 NPE）
        single<ExternalServiceConfig> { mockk(relaxed = true) }
        single<ExternalServiceQuotaConfig> { mockk(relaxed = true) }
        single<ExternalServiceCacheConfig> { mockk(relaxed = true) }
    }

    @AfterEach
    fun tearDown() {
        // 取消 sendHandlerScope，释放 Dispatchers.IO 线程，避免非守护线程阻止 JVM 退出
        try {
            GlobalContext.get().get<CoroutineScope>(named("serverScope")).cancel()
        } catch (_: Exception) {}
        stopKoin()
    }

    @Test
    fun allPhaseComponentsAreResolvable() {
        startKoin {
            modules(frameworkModule, serviceKoinModule, userHandlerModule, chatHandlerModule, conversationHandlerModule, friendHandlerModule, messageReliabilityModule, buildMockModule())
        }

        // Phase 6 基础设施组件
        assertNotNull(GlobalContext.get().get<UserStreamRegistry>())
        assertNotNull(GlobalContext.get().get<PushService>())

        // Phase 6 Handler
        assertNotNull(GlobalContext.get().get<SendMessageHandler>())
        assertNotNull(GlobalContext.get().get<PullMessagesHandler>())
        assertNotNull(GlobalContext.get().get<ReadReportHandler>())

        // SendMessageHandler 的 named scope 可解析
        assertNotNull(GlobalContext.get().get<CoroutineScope>(named("serverScope")))

        // Phase 7 基础设施
        assertNotNull(GlobalContext.get().get<ConversationLockManager>())

        // Phase 8 Friend Handler 可解析
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendRejectHandler>())
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendRequestsHandler>())
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendListHandler>())
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendDeleteHandler>())
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendAddHandler>())
        assertNotNull(GlobalContext.get().get<com.nebula.gateway.handler.friend.FriendAcceptHandler>())
    }

    /**
     * 穷尽解析测试 — 加载完整生产图（含 external/sensitiveWord/handlerCollector 模块），
     * 强制构造 [Handler] 与全部 11 个 Service Facade，确认 [serviceKoinModule] 的
     * `facade()`/`singleOf` 注册在真实装配下对**每个**对象都可解析（无 InstanceCreationException）。
     */
    @Test
    fun allServicesAndHandlersResolveExhaustively() {
        startKoin {
            modules(
                frameworkModule,
                serviceKoinModule,
                userHandlerModule,
                chatHandlerModule,
                conversationHandlerModule,
                friendHandlerModule,
                messageReliabilityModule,
                externalHandlerModule,
                sensitiveWordHandlerModule,
                handlerCollectorModule,
                buildMockModule()
            )
        }

        // 强制构造全部 Handler（经 AllHandlerCollector.getAll<Handler<*,*>>()），transitively 解析所有注入的 Service
        val allHandlers = GlobalContext.get().getAll<Handler<*, *>>()
        assertTrue(allHandlers.isNotEmpty(), "至少应注册到一个 Handler")

        // 显式解析全部 11 个 Service Facade + 子服务 + 外部编排器 + 双接口委托
        assertNotNull(GlobalContext.get().get<UserService>())
        assertNotNull(GlobalContext.get().get<UserPrivacyService>())
        assertNotNull(GlobalContext.get().get<OnlineStatusService>())
        assertNotNull(GlobalContext.get().get<MessageService>())
        assertNotNull(GlobalContext.get().get<SeqService>())
        assertNotNull(GlobalContext.get().get<ConversationService>())
        assertNotNull(GlobalContext.get().get<GroupService>())
        assertNotNull(GlobalContext.get().get<ConversationQueryService>())
        assertNotNull(GlobalContext.get().get<FriendService>())
        assertNotNull(GlobalContext.get().get<FriendRequestService>())
        assertNotNull(GlobalContext.get().get<FriendshipService>())
        assertNotNull(GlobalContext.get().get<WeatherService>())
        assertNotNull(GlobalContext.get().get<SearchService>())
        assertNotNull(GlobalContext.get().get<GeoIpService>())
        assertNotNull(GlobalContext.get().get<DeadLetterService>())
        assertNotNull(GlobalContext.get().get<DeadLetterCallback>()) // 双接口委托
        assertNotNull(GlobalContext.get().get<ExternalServiceOrchestrator>())
    }
}
