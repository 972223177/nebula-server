package com.nebula.gateway.di

import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.delivery.DeliveryAckHandler
import com.nebula.gateway.handler.message.MessageSeqHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 聊天和消息 Handler Koin 模块 — 注册 Chat & Message 相关的 Handler 和组件。
 *
 * Handler 依赖 Service 层而非直接依赖 Repository。
 */
val chatHandlerModule = module {
    single { UserStreamRegistry() }
    single { PushService(get(), get(), get()) }

    // Handler 注册 — 依赖 Service 层
    single { SendMessageHandler(get(), get(), get(), get(), get(), get(named("serverScope"))) } bind com.nebula.gateway.handler.Handler::class
    single { PullMessagesHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { ReadReportHandler(get(), get(), get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class
    single { MessageSeqHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { DeliveryAckHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class

}
