package com.nebula.gateway.handler.chat

import com.nebula.gateway.dispatcher.HandlerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * ChatHandlerCollector 单元测试。
 *
 * 验证 registerAll() 正确注册 chat/send、message/pull、message/read、message/seq、message/delivery_ack 五个 Handler。
 */
class ChatHandlerCollectorTest {

    @Test
    fun registerAllShouldRegisterChatAndMessageHandlers() = runTest {
        val registry = HandlerRegistry()
        val collector = ChatHandlerCollector(
            sendMessageHandler = mockk { every { method } returns "chat/send" },
            pullMessagesHandler = mockk { every { method } returns "message/pull" },
            readReportHandler = mockk { every { method } returns "message/read" },
            messageSeqHandler = mockk { every { method } returns "message/seq" },
            deliveryAckHandler = mockk { every { method } returns "message/delivery_ack" }
        )

        collector.registerAll(registry)

        assertNotNull(registry.get("chat/send"), "chat/send 应已注册")
        assertNotNull(registry.get("message/pull"), "message/pull 应已注册")
        assertNotNull(registry.get("message/read"), "message/read 应已注册")
        assertNotNull(registry.get("message/seq"), "message/seq 应已注册")
        assertNotNull(registry.get("message/delivery_ack"), "message/delivery_ack 应已注册")
    }
}
