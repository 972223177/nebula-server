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
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 发送好友申请 Handler（D-51, D-52, D-54）。
 *
 * 职责：
 * - 委托 FriendService 处理业务逻辑
 * - 推送 FRIEND_REQUEST / FRIEND_ACCEPTED
 *
 * @param friendService 好友业务服务
 * @param pushService 推送服务
 * @param lockManager 会话级互斥锁管理器
 */
class FriendAddHandler(
    private val friendService: FriendService,
    private val pushService: PushService,
    private val lockManager: ConversationLockManager
) : Handler<FriendAddReq, FriendAddResp> {

    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        val fromUid = session.userId

        val result = friendService.addFriend(req, fromUid)

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
}
