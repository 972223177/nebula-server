package com.nebula.gateway.handler.chat

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.message.MessageSeqHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.di.register

/**
 * 聊天消息 Handler 收集器 — 注册 Chat 和 Message 模块的所有 Handler（Phase 6）。
 */
class ChatHandlerCollector(
    private val sendMessageHandler: SendMessageHandler,
    private val pullMessagesHandler: PullMessagesHandler,
    private val readReportHandler: ReadReportHandler,
    private val messageSeqHandler: MessageSeqHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(sendMessageHandler)
        registry.register(pullMessagesHandler)
        registry.register(readReportHandler)
        registry.register(messageSeqHandler)
    }
}
