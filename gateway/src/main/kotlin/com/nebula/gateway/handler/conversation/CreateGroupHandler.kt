package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.CreateGroupResp
import com.nebula.chat.conversation.GroupCreatedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 创建群聊 Handler — method = "conversation/create_group"（D-02, D-05, D-10, D-19）。
 *
 * 串行事务内完成：生成 UUID → 创建群会话 → 创建群主成员记录 → 批量创建初始成员记录。
 * 使用 ConversationLockManager 确保同会话并发安全，TransactionTemplate 保证事务原子性（D-19）。
 * 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者，D-10）。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板
 * @param pushService 推送服务
 */
class CreateGroupHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<CreateGroupReq, CreateGroupResp> {

    override val method: String = "conversation/create_group"

    companion object {
        private const val MAX_MEMBERS = 200
        /** 群聊类型常量 */
        private const val CONV_TYPE_GROUP = 2
        /** 群主角色常量 */
        private const val ROLE_OWNER = "owner"
        /** 成员角色常量 */
        private const val ROLE_MEMBER = "member"
    }

    override suspend fun handle(req: CreateGroupReq): CreateGroupResp {
        val session = currentCoroutineContext().requireSession()

        // 参数校验：群名称非空
        val name = req.name.takeIf { it.isNotBlank() }
            ?: throw ConversationException(BizCode.INVALID_PARAM, "群名称不能为空")

        if (name.length > 128) {
            throw ConversationException(BizCode.INVALID_PARAM, "群名称不能超过128个字符")
        }

        // 创建者不能在 member_uids 中（D-10）
        if (session.userId in req.memberUidsList) {
            throw ConversationException(BizCode.INVALID_PARAM, "创建者不能在初始成员列表中")
        }

        // 初始成员数 + 创建者 ≤ 200（D-05）
        val totalMemberCount = 1 + req.memberUidsList.size
        if (totalMemberCount > MAX_MEMBERS) {
            throw ConversationException(BizCode.GROUP_FULL, "群成员数不能超过${MAX_MEMBERS}")
        }

        // 生成 UUID 作为 conversation_id（D-02）
        val convId = UUID.randomUUID().toString()
        val now = LocalDateTime.now()

        // 锁内执行事务（D-19：先锁后事务）
        val memberUids = req.memberUidsList
        lockManager.withLock(convId) {
            transactionTemplate.execute {
                // 创建群会话
                val conv = ConversationEntity(
                    type = CONV_TYPE_GROUP,
                    name = name,
                    memberCount = totalMemberCount
                )
                conv.id = convId
                conv.createdAt = now
                conv.updatedAt = now
                conversationRepository.save(conv)

                // 创建群主成员记录
                val ownerMember = ConversationMemberEntity(
                    conversationId = convId,
                    userId = session.userId,
                    role = ROLE_OWNER
                )
                ownerMember.joinedAt = now
                conversationMemberRepository.save(ownerMember)

                // 批量创建初始成员记录
                memberUids.forEach { uid ->
                    val member = ConversationMemberEntity(
                        conversationId = convId,
                        userId = uid,
                        role = ROLE_MEMBER
                    )
                    member.joinedAt = now
                    conversationMemberRepository.save(member)
                }
            }
        }

        // 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者，D-10）
        val payload = GroupCreatedPayload.newBuilder()
            .setConversationId(convId)
            .setName(name)
            .setCreatorUid(session.userId)
            .build()
        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.GROUP_CREATED,
            payloadBytes = payload.toByteString(),
            excludeUids = setOf(session.userId)
        )

        return CreateGroupResp.newBuilder()
            .setConversationId(convId)
            .setName(name)
            .build()
    }
}
