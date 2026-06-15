package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.admin.DeadLetterCompensator
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.delivery.RedisDeliveryTracker
import com.nebula.gateway.handler.admin.AdminHandlerCollector
import com.nebula.gateway.handler.admin.DeadLetterQueryHandler
import com.nebula.gateway.handler.admin.RetryDeadLetterHandler
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.DeadLetterRepository
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.sequence.SeqService
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * MessageReliabilityModule Koin 装配测试（Phase 10）。
 *
 * 单容器 + 多断言模式：一次 startKoin，所有解析验证在同一个 Koin 容器中完成。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MessageReliabilityModuleTest {

    /** 外部 mock 依赖 */
    private val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    private val deadLetterRepo = mockk<DeadLetterRepository>()
    private val messageQueueRepo = mockk<MessageQueueRepository>()
    private val idGenerator = mockk<SnowflakeIdGenerator>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 构建外部依赖模块（模拟 Repository 层和基础设施）。
     */
    private fun buildExternalModule() = module {
        single { redisConnection as StatefulRedisConnection<String, String> }
        single { deadLetterRepo }
        single { messageQueueRepo }
        single { idGenerator }
        single(named("sendHandlerScope")) { scope }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /**
     * 验证 messageReliabilityModule 中所有组件均可正确解析。
     *
     * 单容器启动，避免 7 次重复 startKoin 的开销。
     */
    @Test
    fun messageReliabilityModuleShouldResolveAllComponents() {
        startKoin { modules(messageReliabilityModule, buildExternalModule()) }
        val koin = GlobalContext.get()

        assertNotNull(koin.get<SeqService>())
        assertNotNull(koin.get<RedisDeliveryTracker>())
        assertNotNull(koin.get<DeliveryTrackingService>())
        assertNotNull(koin.get<DeadLetterService>())
        assertNotNull(koin.get<DeadLetterCompensator>())
        assertNotNull(koin.get<DeadLetterQueryHandler>())
        assertNotNull(koin.get<RetryDeadLetterHandler>())
    }

    /**
     * 验证 AdminHandlerCollector 可从已解析的 Handler 构造。
     */
    @Test
    fun messageReliabilityModuleShouldConstructAdminHandlerCollector() {
        startKoin { modules(messageReliabilityModule, buildExternalModule()) }
        val deadLetterQueryHandler = GlobalContext.get().get<DeadLetterQueryHandler>()
        val retryDeadLetterHandler = GlobalContext.get().get<RetryDeadLetterHandler>()
        val collector = AdminHandlerCollector(deadLetterQueryHandler, retryDeadLetterHandler)
        assertNotNull(collector)
    }
}
