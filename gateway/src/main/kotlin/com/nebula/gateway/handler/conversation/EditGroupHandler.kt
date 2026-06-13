package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupUpdatedPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext
import com.nebula.gateway.handler.requireSession

/**
 * 编辑群信息 Handler — method = "conversation/edit_group_info"（D-15）。
 *
 * 委托 ConversationService 处理编辑业务逻辑，推送 GROUP_UPDATED 给所有成员。
 *
 * @param conversationService 会话业务服务
 * @param pushService 推送服务
 */
class EditGroupHandler(
    private val conversationService: ConversationService,
    private val pushService: PushService
) : Handler<EditGroupReq, Response> {

    override val method: String = "conversation/edit_group_info"

    override suspend fun handle(req: EditGroupReq): Response {
        val session = currentCoroutineContext().requireSession()
        conversationService.editGroupInfo(req, session.userId)

        // 异步推送 GROUP_UPDATED（D-15）
        val hasName = req.hasName() && req.name.isNotBlank()
        val hasAvatar = req.hasAvatarUrl() && req.avatarUrl.isNotBlank()

        val payload = GroupUpdatedPayload.newBuilder().apply {
            conversationId = req.conversationId
            if (hasName) name = req.name
            if (hasAvatar) avatarUrl = req.avatarUrl
        }.build()
        pushService.pushConversationEvent(
            convId = req.conversationId,
            eventType = PushEventType.GROUP_UPDATED,
            payloadBytes = payload.toByteString()
        )

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
