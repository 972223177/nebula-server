package com.nebula.gateway.di

import com.nebula.gateway.handler.chat.send.DedupStep
import com.nebula.gateway.handler.chat.send.FriendCheckStep
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.chat.send.SendMessageStep
import com.nebula.gateway.handler.chat.send.ValidateStep
import com.nebula.gateway.handler.chat.send.WriteStep
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
 * Phase 6: Chat & Message（D-13 Step 链 + PushService + 推送基础设施）
 */
val chatHandlerModule = module {
    /** SendMessageHandler 使用 IO 调度器的后台协程执行 fire-and-forget 推送 */
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { UserStreamRegistry() }                                                      // D-01
    single { PushService(get(), get()) }                                                  // PushService(UserStreamRegistry, ConversationMemberRepository)
    // Step 链注册 — 显式构建列表传递给 SendMessageHandler（D-13）
    single {
        listOf<SendMessageStep>(
            ValidateStep(get()),                            // ValidateStep(ConversationMemberRepository)
            FriendCheckStep(get(), get()),                  // FriendCheckStep(FriendshipRepository, ConversationRepository)
            DedupStep(get()),                               // DedupStep(RedisConnection)
            WriteStep(get(), get(), get())                  // WriteStep(SnowflakeIdGenerator, MessageQueueRepository, RedisConnection)
        )
    }
    single { SendMessageHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) }
    single { PullMessagesHandler(get(), get(), get()) }                                    // Phase 7 新增第3参数 ConvMemberRepo
    single { ReadReportHandler(get(), get(), get(), get()) }

    // HandlerCollector 注册
    single<HandlerCollector> { ChatHandlerCollector(get(), get(), get()) }
}
