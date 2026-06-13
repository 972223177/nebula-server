package com.nebula.gateway.handler.message

import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.chat.MessageService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 消息拉取 Handler — method = "message/pull"（D-17 ~ D-21）。
 *
 * 委托 MessageService 处理消息拉取逻辑。
 *
 * @param messageService 消息业务服务
 */
class PullMessagesHandler(
    private val messageService: MessageService
) : Handler<PullMessagesReq, PullMessagesResp> {

    override val method: String = "message/pull"

    override suspend fun handle(req: PullMessagesReq): PullMessagesResp {
        val session = currentCoroutineContext().requireSession()
        return messageService.pullMessages(req, session.userId)
    }
}
