package com.nebula.server

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.di.frameworkModule
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.gateway.di.handlerModule
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.chat.send.SendMessageStep
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.SessionRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.UserRepository
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
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
 * Koin 容器验证测试 — 验证 Phase 6 所有组件可在 Koin 容器中正确解析（D-01, D-13）。
 *
 * 测试方法：启动 Koin 并加载 gateway 模块 + 外部依赖模块，
 * 然后逐一解析 Phase 6 的注册组件，确保无 InstanceCreationException。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class KoinVerificationTest {

    /** 构建外部依赖模块 — 使用 mock 对象代替真实基础设施 */
    private fun buildMockModule() = module {
        single<UserRepository> { mockk() }
        single<SessionRepository> { mockk() }
        single<ConversationRepository> { mockk() }
        single<ConversationMemberRepository> { mockk() }
        single<MessageRepository> { mockk() }
        single<MessageQueueRepository> { mockk() }
        single<StatefulRedisConnection<String, String>> { mockk(relaxed = true) }
        single<SnowflakeIdGenerator> { mockk() }
        single<PrivacyRepository> { PrivacyRepository(get(), get()) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `Phase 6 all components are resolvable`() {
        startKoin {
            modules(frameworkModule, handlerModule, buildMockModule())
        }

        // Phase 6 基础设施组件
        assertNotNull(GlobalContext.get().get<UserStreamRegistry>())
        assertNotNull(GlobalContext.get().get<PushService>())

        // Phase 6 Handler
        assertNotNull(GlobalContext.get().get<SendMessageHandler>())
        assertNotNull(GlobalContext.get().get<PullMessagesHandler>())
        assertNotNull(GlobalContext.get().get<ReadReportHandler>())

        // SendMessageHandler 的 named scope 可解析
        assertNotNull(GlobalContext.get().get<CoroutineScope>(named("sendHandlerScope")))

        // Step 链可解析
        val steps = GlobalContext.get().get<List<SendMessageStep>>()
        assertTrue(steps.isNotEmpty(), "SendMessageStep 列表不应为空")
    }
}

