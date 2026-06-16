package com.nebula.gateway.di

import com.nebula.gateway.admin.DeadLetterCompensator
import com.nebula.gateway.delivery.DeliveryHandlerCollector
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.delivery.RedisDeliveryTracker
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.admin.AdminHandlerCollector
import com.nebula.gateway.handler.admin.DeadLetterQueryHandler
import com.nebula.gateway.handler.admin.RetryDeadLetterHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 消息可靠性 Koin 模块（Phase 10）— 注册序列号、投递跟踪、死信及补偿相关组件。
 *
 * 包含：
 * - [SeqService]：会话序列号服务
 * - [RedisDeliveryTracker]：Redis 投递状态跟踪
 * - [DeliveryTrackingService]：投递三态跟踪服务
 * - [DeadLetterService]：死信记录服务
 * - [DeadLetterCompensator]：死信补偿定时任务
 * - [DeadLetterQueryHandler]、[RetryDeadLetterHandler]：Admin 管理 Handler
 * - [DeliveryHandlerCollector]、[AdminHandlerCollector]：Handler 注册收集器
 */
val messageReliabilityModule = module {
    // 投递跟踪
    single { RedisDeliveryTracker(get()) }
    single { DeliveryTrackingService(get()) }

    // 死信补偿
    single { DeadLetterCompensator(get(), get(named("sendHandlerScope"))) }

    // Admin 管理 Handler
    single { DeadLetterQueryHandler(get()) }
    single { RetryDeadLetterHandler(get()) }

    // HandlerCollector 注册
    single<HandlerCollector> { DeliveryHandlerCollector() }
    single<HandlerCollector> { AdminHandlerCollector(get(), get()) }
}
