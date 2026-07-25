package com.nebula.gateway.handler.conversation
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.conversation.CreatePrivateConversationReq
import com.nebula.chat.conversation.CreatePrivateConversationResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 创建私聊会话 Handler — method = "conversation/create_private"。
 *
 * 校验好友关系后创建或恢复与目标用户的私聊会话。
 * 双方 member 记录若已软删则恢复。
 *
 * @param conversationService 会话业务服务
 */
class CreatePrivateConversationHandler(
    private val conversationService: ConversationService
) : Handler<CreatePrivateConversationReq, CreatePrivateConversationResp> {

    override val method: String = MethodNames.Conversation.CREATE_PRIVATE

    override suspend fun handle(req: CreatePrivateConversationReq): CreatePrivateConversationResp {
        val session = currentCoroutineContext().requireSession()
        val convId = conversationService.createPrivateConversation(session.userId, req.uid)
        return CreatePrivateConversationResp.newBuilder()
            .setConversationId(convId)
            .build()
    }
}
