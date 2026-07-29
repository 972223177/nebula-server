package com.nebula.gateway.di

import com.nebula.gateway.admin.DeadLetterCompensator
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.delivery.RedisDeliveryTracker
import com.nebula.gateway.handler.admin.DeadLetterQueryHandler
import com.nebula.gateway.handler.admin.RetryDeadLetterHandler
import org.koin.core.qualifier.named
import org.koin.dsl.bind
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
 * - Admin Handler 经 [handlerCollectorModule] 的 [com.nebula.gateway.handler.AllHandlerCollector] 统一注册（getAll 自动发现）
 */
val messageReliabilityModule = module {
    // 投递跟踪
    single { RedisDeliveryTracker(get()) }
    single { DeliveryTrackingService(get()) }

    // 死信补偿
    single { DeadLetterCompensator(get(), get(named("serverScope"))) }

    // Admin 管理 Handler
    single { DeadLetterQueryHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { RetryDeadLetterHandler(get()) } bind com.nebula.gateway.handler.Handler::class

}
