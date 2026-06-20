package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.chat.conversation.MemberJoinedPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 邀请成员 Handler — method = "conversation/invite_member"（D-03, D-05, D-19）。
 *
 * 职责：
 * - 使用会话级互斥锁（ConversationLockManager）保证同一会话邀请串行执行（D-19）
 * - 委托 ConversationService 处理邀请成员业务逻辑（Service 内部已通过 JpaTxRunner 包裹事务）
 * - 锁内业务完成后异步推送 MEMBER_JOINED 给现有成员
 *
 * @param conversationService 会话业务服务
 * @param lockManager 会话级互斥锁管理器
 * @param pushService 推送服务
 */
class InviteMemberHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val pushService: PushService
) : Handler<InviteMemberReq, Response> {

    override val method: String = "conversation/invite_member"

    override suspend fun handle(req: InviteMemberReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 会话级锁 + Service 内置事务，保证批量邀请与 memberCount 原子性
        val newMemberUids = lockManager.withLock(req.conversationId) {
            conversationService.inviteMember(req, session.userId)
        }

        // 推送 MEMBER_JOINED 给现有成员
        val payload = MemberJoinedPayload.newBuilder()
            .setConversationId(req.conversationId)
            .addAllUids(newMemberUids)
            .setInviterUid(session.userId)
            .build()
        pushService.pushConversationEvent(
            convId = req.conversationId,
            eventType = PushEventType.MEMBER_JOINED,
            payloadBytes = payload.toByteString(),
            excludeUids = newMemberUids.toSet()
        )

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
