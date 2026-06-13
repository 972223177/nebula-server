package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 获取好友申请列表 Handler（D-41）。
 *
 * 委托 FriendService 查询好友申请列表。
 *
 * @param friendService 好友业务服务
 */
class FriendRequestsHandler(
    private val friendService: FriendService
) : Handler<FriendRequestsReq, FriendRequestsResp> {

    override val method: String = "friend/requests"

    override suspend fun handle(req: FriendRequestsReq): FriendRequestsResp {
        val session = currentCoroutineContext().requireSession()
        return friendService.getFriendRequests(req, session.userId)
    }
}
