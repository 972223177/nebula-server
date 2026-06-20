package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate

/**
 * 接受好友申请 Handler（D-43, D-45, D-52）。
 *
 * 职责：
 * - 委托 FriendService 处理接受申请业务逻辑
 * - 推送 FRIEND_ACCEPTED 给双方
 *
 * @param friendService 好友业务服务
 * @param pushService 推送服务
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板（D-79）
 */
class FriendAcceptHandler(
    private val friendService: FriendService,
    private val pushService: PushService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate
) : Handler<FriendAcceptReq, Response> {

    override val method: String = "friend/accept"

    override suspend fun handle(req: FriendAcceptReq): Response {
        val session = currentCoroutineContext().requireSession()

        // D-79/H16: 事务包裹确保跨 Repository 写入原子性
        // 修复（2026-06-20）：包裹 withContext(Dispatchers.IO) 释放调用者协程
        val result = withContext(Dispatchers.IO) {
            transactionTemplate.execute {
                runBlocking {
                    friendService.acceptFriendRequest(req, session.userId)
                }
            }!!
        }

        // 推送 FRIEND_ACCEPTED 给双方
        val acceptedPayload = FriendAcceptedPayload.newBuilder()
            .setUid(session.userId)
            .setConversationId(result.convId)
            .build()
        pushService.pushEventToUser(result.fromUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
        pushService.pushEventToUser(result.toUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
