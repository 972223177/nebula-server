package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.ConvListReq
import com.nebula.chat.conversation.ConvListResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 会话列表 Handler — method = "conversation/list"（D-01, D-13, D-21）。
 *
 * 委托 ConversationService 查询用户的会话列表。
 *
 * @param conversationService 会话业务服务
 */
class ListConversationsHandler(
    private val conversationService: ConversationService
) : Handler<ConvListReq, ConvListResp> {

    override val method: String = "conversation/list"

    override suspend fun handle(req: ConvListReq): ConvListResp {
        val session = currentCoroutineContext().requireSession()
        return conversationService.listConversations(
            userId = session.userId,
            cursor = req.cursor,
            limit = req.limit
        )
    }
}
