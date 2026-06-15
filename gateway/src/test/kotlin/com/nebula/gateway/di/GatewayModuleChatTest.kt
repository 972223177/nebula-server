package com.nebula.gateway.di

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.message.MessageSeqHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.chat.MessageService
import com.nebula.service.sequence.SeqService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * Chat/Message Handler 注册测试（D-32）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleChatTest : HandlerRegistryTestBase() {

    /** Service 层 mock */
    private val pushService = mockk<PushService>()
    private val messageService = mockk<MessageService>()
    private val seqService = mockk<SeqService>()

    private fun buildHandlerModule() = module {
        single { pushService }
        single { messageService }
        single { seqService }
        single { scope }
        single { UserStreamRegistry() }

        single { SendMessageHandler(messageService, pushService, get(), get(), get(), get()) }
        single { PullMessagesHandler(messageService) }
        single { ReadReportHandler(messageService, get(), get(), pushService, get()) }
        single { MessageSeqHandler(seqService) }
    }

    @Test
    fun `ChatHandlerCollector 注册全部 4 个 message handler`() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        val collector = com.nebula.gateway.handler.chat.ChatHandlerCollector(
            GlobalContext.get().get<SendMessageHandler>(),
            GlobalContext.get().get<PullMessagesHandler>(),
            GlobalContext.get().get<ReadReportHandler>(),
            GlobalContext.get().get<MessageSeqHandler>()
        )
        collector.registerAll(registry)

        assertNotNull(registry.get("chat/send"))
        assertNotNull(registry.get("message/pull"))
        assertNotNull(registry.get("message/read"))
    }
}
