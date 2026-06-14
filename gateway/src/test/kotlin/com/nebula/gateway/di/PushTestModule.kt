package com.nebula.gateway.di

import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import io.mockk.mockk
import org.koin.dsl.module

/**
 * PushService 所需的 Koin 模块 — 供需要 PushService 的 Handler 测试使用。
 *
 * 独立 Koin 模块而非直接在测试中注册 mock，避免每个领域测试类重复声明。
 */
val PushTestModule = module {
    single { UserStreamRegistry() }
    single { mockk<DeliveryTrackingService>() }
    single { mockk<PushService>() }
}
