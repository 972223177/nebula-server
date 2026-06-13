package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.chat.conversation.MemberJoinedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * 邀请成员 Handler — method = "conversation/invite_member"（D-03, D-05, D-19）。
 *
 * 邀请入群无需审批，直接加入（D-03）。
 * 验证会话未解散、邀请者是成员、被邀请者不重复、群人数上限。
 * 使用 ConversationLockManager + TransactionTemplate 保证原子性（D-19）。
 * 事务提交后异步推送 MEMBER_JOINED 给现有成员。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class InviteMemberHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<InviteMemberReq, Response> {

    override val method: String = "conversation/invite_member"

    companion object {
        private const val MAX_MEMBERS = 200
        private const val STATUS_DISSOLVED = 1
        private const val ROLE_MEMBER = "member"
    }

    override suspend fun handle(req: InviteMemberReq): Response {
        val session = currentCoroutineContext().requireSession()
        val convId = req.conversationId
        val inviteUids = req.uidsList

        if (inviteUids.isEmpty()) {
            throw ConversationException(BizCode.INVALID_PARAM, "邀请列表不能为空")
        }

        // 获取会话信息并验证未解散
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(convId).orElse(null)
        } ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")

        if (conversation.status == STATUS_DISSOLVED) {
            throw ConversationException(BizCode.GROUP_DISSOLVED, "群聊已解散")
        }

        // 验证邀请者是成员
        withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(convId, session.userId)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        // 批量检查已存在成员（过滤重复邀请）
        val existingMembers = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserIds(convId, inviteUids)
        }
        val existingUids = existingMembers.map { it.userId }.toSet()
        val newUids = inviteUids.filter { it !in existingUids }

        if (newUids.isEmpty()) {
            throw ConversationException(BizCode.ALREADY_IN_GROUP, "所有被邀请者已在群中")
        }

        // 上限检查（D-05）
        val activeCount = withContext(Dispatchers.IO) {
            conversationMemberRepository.countActiveByConversationId(convId)
        }
        if (activeCount + newUids.size > MAX_MEMBERS) {
            throw ConversationException(BizCode.GROUP_FULL, "群成员数已达上限")
        }

        // 锁内执行事务（D-19）
        val now = LocalDateTime.now()
        lockManager.withLock(convId) {
            transactionTemplate.execute {
                newUids.forEach { uid ->
                    val member = ConversationMemberEntity(
                        conversationId = convId,
                        userId = uid,
                        role = ROLE_MEMBER
                    )
                    member.joinedAt = now
                    conversationMemberRepository.save(member)
                }

                // 更新 memberCount
                val conv = conversationRepository.findById(convId).get()
                conv.memberCount = (activeCount + newUids.size).toInt()
                conversationRepository.save(conv)
            }
        }

        // 异步推送 MEMBER_JOINED 给现有成员
        val payload = MemberJoinedPayload.newBuilder()
            .setConversationId(convId)
            .addAllUids(newUids)
            .setInviterUid(session.userId)
            .build()
        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.MEMBER_JOINED,
            payloadBytes = payload.toByteString(),
            excludeUids = newUids.toSet()
        )

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
