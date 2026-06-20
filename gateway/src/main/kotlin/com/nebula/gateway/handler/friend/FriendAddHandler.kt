package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendRequestPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.currentCoroutineContext

/**
 * 发送好友申请 Handler（D-51, D-52, D-54）。
 *
 * 职责：
 * - 委托 FriendService 处理业务逻辑（Service 内部已通过 JpaTxRunner 包裹事务，D-79）
 * - ConstraintViolationException 幂等 catch 处理双向竞赛（D-80）
 * - 推送 FRIEND_REQUEST / FRIEND_ACCEPTED
 *
 * @param friendService 好友业务服务
 * @param pushService 推送服务
 * @param lockManager 会话级互斥锁管理器（保留以维持 API 兼容，Friend 流程不依赖会话级锁）
 */
class FriendAddHandler(
    private val friendService: FriendService,
    private val pushService: PushService,
    @Suppress("unused") private val lockManager: ConversationLockManager
) : Handler<FriendAddReq, FriendAddResp> {

    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        val fromUid = session.userId

        // Service 内部事务（D-79 + D-80）
        // PersistenceException 幂等 catch 处理双向竞赛
        val result = try {
            friendService.addFriend(req, fromUid)
        } catch (e: PersistenceException) {
            val isConstraintViolation = e.message?.contains("Duplicate", ignoreCase = true) == true ||
                e.message?.contains("ConstraintViolation", ignoreCase = true) == true
            if (isConstraintViolation) {
                // D-80: UK 冲突表示好友关系已存在（双向竞赛中的并行请求被 DB 唯一约束拦截）
                logger.warn(e) { "好友关系已存在，幂等返回: fromUid=$fromUid, toUid=${req.toUid}" }
                val smaller = minOf(fromUid, req.toUid)
                val larger = maxOf(fromUid, req.toUid)
                val existingFriendship = friendService.findFriendshipBetween(smaller, larger)
                if (existingFriendship != null && existingFriendship.deleted == 0) {
                    // 已存在未删除的好友关系 → 直接返回 ALREADY_FRIEND
                    throw FriendException(BizCode.ALREADY_FRIEND)
                }
                // 防御性编程：UK 冲突意味着 DB 中已存在记录，若走到此处说明出现异常状态
                // （如 existingFriendship==null 或 deleted!=0），按冲突处理返回 ALREADY_FRIEND
                throw FriendException(BizCode.ALREADY_FRIEND)
            }
            throw e
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
