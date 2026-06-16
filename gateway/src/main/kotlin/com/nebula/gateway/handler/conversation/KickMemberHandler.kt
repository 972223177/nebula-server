package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.chat.conversation.MemberKickedPayload
import com.nebula.chat.conversation.MemberLeftPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking

/**
 * 踢出成员 Handler — method = "conversation/kick_member"（D-04, D-14, D-19）。
 *
 * 职责：
 * - 委托 ConversationService 处理踢人业务逻辑
 * - 推送 MEMBER_KICKED 给被踢者 + MEMBER_LEFT 给剩余成员
 *
 * @param conversationService 会话业务服务
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class KickMemberHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
    private val pushService: PushService
) : Handler<KickMemberReq, Response> {

    override val method: String = "conversation/kick_member"

    override suspend fun handle(req: KickMemberReq): Response {
        val session = currentCoroutineContext().requireSession()

        // D-79/H19: 锁 + 事务包裹，确保踢人 + memberCount 原子性
        val targetUid = lockManager.withLock(req.conversationId) {
            transactionTemplate.execute {
                runBlocking {
                    conversationService.kickMember(req, session.userId)
                }
            }!!
        }

        // 推送 MEMBER_KICKED 给被踢者（D-14）
        val kickPayload = MemberKickedPayload.newBuilder()
            .setConversationId(req.conversationId)
            .setUid(targetUid)
            .build()
        pushService.pushEventToUser(
            targetUid = targetUid,
            eventType = PushEventType.MEMBER_KICKED,
            payloadBytes = kickPayload.toByteString()
        )

        // 推送 MEMBER_LEFT 给剩余成员
        val leftPayload = MemberLeftPayload.newBuilder()
            .setConversationId(req.conversationId)
            .setUid(targetUid)
            .build()
        pushService.pushConversationEvent(
            convId = req.conversationId,
            eventType = PushEventType.MEMBER_LEFT,
            payloadBytes = leftPayload.toByteString(),
            excludeUids = setOf(targetUid)
        )

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
