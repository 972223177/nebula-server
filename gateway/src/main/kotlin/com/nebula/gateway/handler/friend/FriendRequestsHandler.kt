package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendRequestItem
import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.ZoneOffset

/**
 * 获取待处理好友申请列表 Handler（D-41）。
 *
 * 查询所有 status=0(pending) 的申请 → 批量获取申请人信息
 * → 构造 FriendRequestsResp（含 requestId/fromUid/fromUsername/fromAvatar/message/status/createdAt）。
 */
class FriendRequestsHandler(
    private val friendRequestRepository: FriendRequestRepository,
    private val userRepository: UserRepository
) : Handler<FriendRequestsReq, FriendRequestsResp> {

    override val method: String = "friend/requests"

    override suspend fun handle(req: FriendRequestsReq): FriendRequestsResp {
        val session = currentCoroutineContext().requireSession()

        val requests = withContext(Dispatchers.IO) {
            friendRequestRepository.findByToUidAndStatusOrderByCreatedAtDesc(session.userId, 0)
        }

        // 批量获取申请人信息
        val fromUids = requests.map { it.fromUid }.distinct()
        val userMap = withContext(Dispatchers.IO) {
            userRepository.findAllById(fromUids).associateBy { it.id }
        }

        val items = requests.map { request ->
            val user = userMap[request.fromUid]
            FriendRequestItem.newBuilder()
                .setRequestId(request.id ?: 0L)
                .setFromUid(request.fromUid)
                .setFromUsername(user?.username ?: "")
                .setFromAvatar(user?.avatar ?: "")
                .setMessage(request.message)
                .setStatus(when (request.status) {
                    0 -> "pending"
                    1 -> "accepted"
                    2 -> "rejected"
                    else -> "unknown"
                })
                .setCreatedAt(request.createdAt?.toEpochSecond(ZoneOffset.UTC)?.times(1000) ?: 0L)
                .build()
        }

        return FriendRequestsResp.newBuilder()
            .addAllRequests(items)
            .build()
    }
}
