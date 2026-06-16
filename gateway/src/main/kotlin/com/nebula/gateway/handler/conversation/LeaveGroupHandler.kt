package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.GroupDissolvedPayload
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.chat.conversation.MemberLeftPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking

/**
 * 退群/解散群 Handler — method = "conversation/leave_group"（D-04, D-09, D-19）。
 *
 * 职责：
 * - 委托 ConversationService 处理退群/解散群业务逻辑
 * - 群主退群 → 解散群：更新 status + 批量软删除 → 推送 GROUP_DISSOLVED
 * - 普通成员退群 → 软删除自己 → 推送 MEMBER_LEFT
 *
 * @param conversationService 会话业务服务
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class LeaveGroupHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
    private val pushService: PushService
) : Handler<LeaveGroupReq, Response> {

    override val method: String = "conversation/leave_group"

    companion object {
        /** 群聊已解散状态常量 */
        private const val STATUS_DISSOLVED = 1

        /** 群主角色常量 */
        private const val ROLE_OWNER = "owner"
    }

    override suspend fun handle(req: LeaveGroupReq): Response {
        val session = currentCoroutineContext().requireSession()
        val convId = req.conversationId

        // 判断是否是群主退群
        val selfMember = conversationService.getMemberRole(convId, session.userId)

        if (selfMember != null && selfMember.role == ROLE_OWNER) {
            // 群主退群 → 解散群（D-09）
            // D-79/H18: 事务包裹确保 member 删除 + conversation 更新原子性
            lockManager.withLock(convId) {
                transactionTemplate.execute {
                    runBlocking {
                        conversationService.dissolveGroup(convId)
                    }
                }
            }

            // 推送 GROUP_DISSOLVED（D-09）
            val payload = GroupDissolvedPayload.newBuilder()
                .setConversationId(convId)
                .build()
            pushService.pushConversationEvent(
                convId = convId,
                eventType = PushEventType.GROUP_DISSOLVED,
                payloadBytes = payload.toByteString()
            )
        } else {
            // 普通成员退群（D-04）
            // D-79/H18: 事务包裹确保 member 删除 + memberCount 原子性
            lockManager.withLock(convId) {
                transactionTemplate.execute {
                    runBlocking {
                        conversationService.leaveGroup(req, session.userId)
                    }
                }
            }

            // 推送 MEMBER_LEFT 给剩余成员（排除退群者自己）
            val payload = MemberLeftPayload.newBuilder()
                .setConversationId(convId)
                .setUid(session.userId)
                .build()
            pushService.pushConversationEvent(
                convId = convId,
                eventType = PushEventType.MEMBER_LEFT,
                payloadBytes = payload.toByteString(),
                excludeUids = setOf(session.userId)
            )
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
