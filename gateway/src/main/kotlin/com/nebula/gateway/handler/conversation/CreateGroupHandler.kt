package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.CreateGroupResp
import com.nebula.chat.conversation.GroupCreatedPayload
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.support.TransactionTemplate

/**
 * 创建群聊 Handler — method = "conversation/create_group"（D-02, D-05, D-10, D-19）。
 *
 * 职责：
 * - 委托 ConversationService 处理创建群业务逻辑
 * - 使用 ConversationLockManager 确保同会话并发安全（D-19）
 * - 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者，D-10）
 *
 * @param conversationService 会话业务服务
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板（D-79）
 * @param pushService 推送服务
 */
class CreateGroupHandler(
    private val conversationService: ConversationService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<CreateGroupReq, CreateGroupResp> {

    override val method: String = "conversation/create_group"

    override suspend fun handle(req: CreateGroupReq): CreateGroupResp {
        val session = currentCoroutineContext().requireSession()

        // D-79/H14: 锁 + 事务包裹，确保跨表写入原子性
        val result = lockManager.withLock("create") {
            transactionTemplate.execute {
                runBlocking {
                    conversationService.createGroup(req, session.userId)
                }
            }!!
        }

        // 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者，D-10）
        val payload = GroupCreatedPayload.newBuilder()
            .setConversationId(result.convId)
            .setName(result.name)
            .setCreatorUid(session.userId)
            .build()
        pushService.pushConversationEvent(
            convId = result.convId,
            eventType = PushEventType.GROUP_CREATED,
            payloadBytes = payload.toByteString(),
            excludeUids = setOf(session.userId)
        )

        return CreateGroupResp.newBuilder()
            .setConversationId(result.convId)
            .setName(result.name)
            .build()
    }
}
