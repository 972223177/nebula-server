package com.nebula.gateway.handler.conversation

import com.nebula.chat.Response
import com.nebula.chat.conversation.DeleteConversationReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 删除会话 Handler — method = "conversation/delete"。
 *
 * 软删除当前用户在该会话的 member 记录，会话从列表中移除。
 * 不影响其他成员和会话本身。
 *
 * @param conversationService 会话业务服务
 */
class DeleteConversationHandler(
    private val conversationService: ConversationService
) : Handler<DeleteConversationReq, Response> {

    override val method: String = "conversation/delete"

    override suspend fun handle(req: DeleteConversationReq): Response {
        val session = currentCoroutineContext().requireSession()
        conversationService.deleteConversation(session.userId, req.conversationId)
        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .setMethod(method)
            .build()
    }
}
