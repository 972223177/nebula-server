package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.GroupDissolvedPayload
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.chat.conversation.MemberLeftPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate

/**
 * 退群/解散群 Handler — method = "conversation/leave_group"（D-04, D-09, D-19）。
 *
 * 群主退群 → 解散群：更新 status=DISSOLVED + 批量软删除所有成员 → 推送 GROUP_DISSOLVED。
 * 普通成员退群 → 软删除自己 + memberCount-- → 推送 MEMBER_LEFT 给剩余成员。
 * 使用 ConversationLockManager + TransactionTemplate 保证原子性（D-19）。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class LeaveGroupHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<LeaveGroupReq, Response> {

    override val method: String = "conversation/leave_group"

    companion object {
        private const val STATUS_DISSOLVED = 1
        private const val ROLE_OWNER = "owner"
    }

    override suspend fun handle(req: LeaveGroupReq): Response {
        val session = currentCoroutineContext().requireSession()
        val convId = req.conversationId

        // 获取会话信息并验证未解散
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(convId).orElse(null)
        } ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")

        if (conversation.status == STATUS_DISSOLVED) {
            throw ConversationException(BizCode.GROUP_DISSOLVED, "群聊已解散")
        }

        // 验证请求者是成员
        val selfMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, session.userId)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        if (selfMember.role == ROLE_OWNER) {
            // 群主退群 → 解散群（D-09）
            lockManager.withLock(convId) {
                transactionTemplate.execute {
                    // 更新会话状态为已解散
                    val conv = conversationRepository.findById(convId).get()
                    conv.status = STATUS_DISSOLVED
                    conversationRepository.save(conv)

                    // 批量软删除所有成员
                    conversationMemberRepository.softDeleteAllByConversationId(convId)
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
            lockManager.withLock(convId) {
                transactionTemplate.execute {
                    // 软删除自己
                    conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, session.userId)

                    // 更新 memberCount
                    val conv = conversationRepository.findById(convId).get()
                    conv.memberCount = (conv.memberCount - 1).coerceAtLeast(0)
                    conversationRepository.save(conv)
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
