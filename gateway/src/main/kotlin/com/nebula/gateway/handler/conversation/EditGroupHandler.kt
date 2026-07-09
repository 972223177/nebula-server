package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupUpdatedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
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
    private val sensitiveWordService: SensitiveWordService,
    private val conversationService: ConversationService,
    private val pushService: PushService
) : Handler<EditGroupReq, Response> {

    override val method: String = "conversation/edit_group_info"

    override suspend fun handle(req: EditGroupReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 敏感词检测：群名称含敏感词则拒绝编辑，直接报接口错误（CONTENT_VIOLATION）。
        // 群名是公开可见文本，采用拒绝策略（与 chat/send 的脱敏策略不同）。
        if (req.hasName() && req.name.isNotBlank() && sensitiveWordService.contains(req.name)) {
            throw BizException(BizCode.CONTENT_VIOLATION, "群名称包含敏感内容")
        }

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
