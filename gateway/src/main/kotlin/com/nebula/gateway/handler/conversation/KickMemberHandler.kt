package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.chat.conversation.MemberKickedPayload
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
 * 踢出成员 Handler — method = "conversation/kick_member"（D-04, D-14, D-19）。
 *
 * 仅群主可以踢人（D-14）。禁止踢群主、踢自己。
 * 使用 ConversationLockManager + TransactionTemplate 保证原子性（D-19）。
 * 事务提交后推送 MEMBER_KICKED 给被踢者 + MEMBER_LEFT 给剩余成员。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class KickMemberHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<KickMemberReq, Response> {

    override val method: String = "conversation/kick_member"

    companion object {
        private const val STATUS_DISSOLVED = 1
        private const val ROLE_OWNER = "owner"
    }

    override suspend fun handle(req: KickMemberReq): Response {
        val session = currentCoroutineContext().requireSession()
        val convId = req.conversationId
        val targetUid = req.uid

        // 验证不能踢自己（D-14）
        if (targetUid == session.userId) {
            throw ConversationException(BizCode.INVALID_PARAM, "不能将自己踢出群聊")
        }

        // 获取会话信息并验证未解散
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(convId).orElse(null)
        } ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")

        if (conversation.status == STATUS_DISSOLVED) {
            throw ConversationException(BizCode.GROUP_DISSOLVED, "群聊已解散")
        }

        // 验证请求者是群主（D-14）
        val selfMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, session.userId)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        if (selfMember.role != ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可踢人")
        }

        // 验证被踢者是成员
        val targetMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, targetUid)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "被踢用户不在群中")

        // 禁止踢群主（D-14）
        if (targetMember.role == ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "不能踢出群主")
        }

        // 锁内执行事务（D-19）
        lockManager.withLock(convId) {
            transactionTemplate.execute {
                // 软删除被踢者
                conversationMemberRepository.softDeleteByConversationIdAndUserId(convId, targetUid)

                // 更新 memberCount
                val conv = conversationRepository.findById(convId).get()
                conv.memberCount = (conv.memberCount - 1).coerceAtLeast(0)
                conversationRepository.save(conv)
            }
        }

        // 推送 MEMBER_KICKED 给被踢者（D-14）
        val kickPayload = MemberKickedPayload.newBuilder()
            .setConversationId(convId)
            .setUid(targetUid)
            .build()
        pushService.pushEventToUser(
            targetUid = targetUid,
            eventType = PushEventType.MEMBER_KICKED,
            payloadBytes = kickPayload.toByteString()
        )

        // 推送 MEMBER_LEFT 给剩余成员（排除被踢者和群主）
        val leftPayload = MemberLeftPayload.newBuilder()
            .setConversationId(convId)
            .setUid(targetUid)
            .build()
        pushService.pushConversationEvent(
            convId = convId,
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
