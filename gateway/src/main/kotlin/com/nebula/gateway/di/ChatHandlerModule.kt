package com.nebula.gateway.di

import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.handler.chat.ChatHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 聊天和消息 Handler Koin 模块 — 注册 Chat & Message 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层而非直接依赖 Repository。
 */
val chatHandlerModule = module {
    /** SendMessageHandler 使用 IO 调度器的后台协程执行 fire-and-forget 推送 */
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { UserStreamRegistry() }
    single { PushService(get(), get(), get()) }

    // Handler 注册 — 依赖 Service 层
    single { SendMessageHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) }
    single { PullMessagesHandler(get()) }
    single { ReadReportHandler(get(), get(), get(), get(), get()) }

    // HandlerCollector 注册
    single<HandlerCollector> { ChatHandlerCollector(get(), get(), get(), get()) }
}
