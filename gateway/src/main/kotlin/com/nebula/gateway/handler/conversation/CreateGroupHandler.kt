package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.chat.conversation.CreateGroupResp
import com.nebula.chat.conversation.GroupCreatedPayload
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate

/**
 * 创建群聊 Handler — method = "conversation/create_group"（D-02, D-05, D-10, D-19）。
 *
 * 职责：
 * - 委托 ConversationService 处理创建群业务逻辑
 * - 事务提交后异步推送 GROUP_CREATED 给初始成员（排除创建者，D-10）
 *
 * 设计说明：创建群聊不修改任何已有会话（全部新行写入），事务本身保证原子性，
 * 不需要会话级互斥锁（D-19 锁用于已有会话的并发修改场景）。
 *
 * @param conversationService 会话业务服务
 * @param transactionTemplate 编程式事务模板（D-79）
 * @param pushService 推送服务
 */
class CreateGroupHandler(
    private val conversationService: ConversationService,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<CreateGroupReq, CreateGroupResp> {

    override val method: String = "conversation/create_group"

    override suspend fun handle(req: CreateGroupReq): CreateGroupResp {
        val session = currentCoroutineContext().requireSession()

        // D-79/H14: 事务包裹确保跨表写入原子性
        // 修复（2026-06-20）：原使用全局字面量 "create" 作为锁 key，导致所有创建群聊请求
        // 串行化为 QPS=1。创建群聊不修改任何已有会话状态（全部新行写入），
        // 事务本身已保证原子性，无需额外的会话级互斥锁。
        // 同步用 withContext(Dispatchers.IO) 释放调用者协程
        val result = withContext(Dispatchers.IO) {
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
