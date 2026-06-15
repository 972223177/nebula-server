package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendRequestPayload
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.service.friend.FriendService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

/**
 * 发送好友申请 Handler（D-51, D-52, D-54）。
 *
 * 职责：
 * - 委托 FriendService 处理业务逻辑
 * - 使用 TransactionTemplate 确保跨 Repository 写入原子性（D-79）
 * - DuplicateKeyException 幂等 catch 处理双向竞赛（D-80）
 * - 推送 FRIEND_REQUEST / FRIEND_ACCEPTED
 *
 * @param friendService 好友业务服务
 * @param pushService 推送服务
 * @param lockManager 会话级互斥锁管理器
 * @param transactionTemplate 编程式事务模板（D-79）
 * @param friendshipRepository 好友关系仓库（用于幂等查询）
 */
class FriendAddHandler(
    private val friendService: FriendService,
    private val pushService: PushService,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val friendshipRepository: FriendshipRepository
) : Handler<FriendAddReq, FriendAddResp> {

    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        val fromUid = session.userId

        // D-79/H15 + D-80: 事务包裹 + DuplicateKeyException 幂等 catch
        val result = try {
            transactionTemplate.execute {
                runBlocking {
                    friendService.addFriend(req, fromUid)
                }
            }!!
        } catch (e: DataIntegrityViolationException) {
            // D-80: UK 冲突表示好友关系已存在（双向竞赛中的并行请求被 DB 唯一约束拦截）
            logger.warn(e) { "好友关系已存在，幂等返回: fromUid=$fromUid, toUid=${req.toUid}" }
            val smaller = minOf(fromUid, req.toUid)
            val larger = maxOf(fromUid, req.toUid)
            val existingFriendship = withContext(Dispatchers.IO) {
                friendshipRepository.findByUserIdAndFriendId(smaller, larger)
            }
            if (existingFriendship != null && existingFriendship.deleted == 0) {
                throw FriendException(BizCode.ALREADY_FRIEND)
            }
            // 理论上不会走到这里；防御性编程
            throw FriendException(BizCode.ALREADY_FRIEND)
        }

        if (result.isMutualAccept) {
            // 双向竞赛：推送 FRIEND_ACCEPTED 给双方
            val acceptedPayload = FriendAcceptedPayload.newBuilder()
                .setUid(result.toUid)
                .setConversationId(result.convId ?: "")
                .build()
            pushService.pushEventToUser(fromUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
            pushService.pushEventToUser(result.toUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
        } else {
            // 普通申请：推送 FRIEND_REQUEST 给目标用户
            val requestPayload = FriendRequestPayload.newBuilder()
                .setRequestId(result.requestId)
                .setFromUid(fromUid)
                .setFromUsername("")
                .setFromAvatar("")
                .setMessage(req.message)
                .build()
            pushService.pushEventToUser(result.toUid, PushEventType.FRIEND_REQUEST, requestPayload.toByteString())
        }

        return FriendAddResp.newBuilder()
            .setRequestId(result.requestId)
            .build()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
