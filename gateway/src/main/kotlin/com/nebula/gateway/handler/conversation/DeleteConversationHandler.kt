package com.nebula.gateway.handler.conversation
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.DeleteConversationReq
import com.nebula.chat.conversation.GroupDissolvedPayload
import com.nebula.chat.conversation.MemberLeftPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.DeleteConversationAction
import kotlinx.coroutines.currentCoroutineContext

/**
 * 删除会话 Handler — method = "conversation/delete"。
 *
 * 业务规则（按国内 IM 主流做法，2026-07 改造）：
 * - 私聊：仅软隐藏当前用户的 member 记录，不影响对方（D-04 单边隐藏）
 * - 群聊：删除 = 退出群组
 *   - 群主删除：解散群，推送 GROUP_DISSOLVED 给所有成员（D-09）
 *   - 普通成员删除：退群，推送 MEMBER_LEFT 给剩余成员（排除自己）
 *
 * 并发保护：群聊分支在 [ConversationLockManager] 会话级锁内执行业务编排（D-19），
 * 避免与踢人/退群/解散群并发冲突。私聊分支无需加锁。
 *
 * 业务编排由 [ConversationService.deleteConversationByType] 收口，Handler 仅做
 * 锁包装 + 推送事件路由，事务由 Service 内部通过 [com.nebula.repository.dao.JpaTxRunner] 管理。
 *
 * @param conversationService 会话业务服务
 * @param lockManager 会话级互斥锁管理器
 * @param pushService 推送服务
 */
class DeleteConversationHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val pushService: PushService
) : Handler<DeleteConversationReq, Response> {

    override val method: String = MethodNames.Conversation.DELETE

    override suspend fun handle(req: DeleteConversationReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId
        val convId = req.conversationId

        // 业务编排：Service 内按 type/role 路由
        // 私聊路径无锁；群聊路径需在锁内执行
        val action = lockManager.withLock(convId) {
            conversationService.deleteConversationByType(userId, convId)
        }

        // 按 Service 返回的动作类型路由推送事件
        // 私聊（PRIVATE_HIDDEN）和幂等的群已解散（GROUP_DISSOLVED_ALREADY）不推送
        when (action) {
            DeleteConversationAction.GROUP_LEFT -> {
                // 普通成员退群：推 MEMBER_LEFT 给剩余成员
                val payload = MemberLeftPayload.newBuilder()
                    .setConversationId(convId)
                    .setUid(userId)
                    .build()
                pushService.pushConversationEvent(
                    convId = convId,
                    eventType = PushEventType.MEMBER_LEFT,
                    payloadBytes = payload.toByteString(),
                    excludeUids = setOf(userId)
                )
            }
            DeleteConversationAction.GROUP_DISSOLVED -> {
                // 群主解散群：推 GROUP_DISSOLVED 给所有成员（含群主自己）
                val payload = GroupDissolvedPayload.newBuilder()
                    .setConversationId(convId)
                    .build()
                pushService.pushConversationEvent(
                    convId = convId,
                    eventType = PushEventType.GROUP_DISSOLVED,
                    payloadBytes = payload.toByteString()
                )
            }
            DeleteConversationAction.PRIVATE_HIDDEN,
            DeleteConversationAction.GROUP_DISSOLVED_ALREADY -> {
                // 私聊隐藏/群已解散：静默处理，不推送
            }
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .setMethod(method)
            .build()
    }
}
