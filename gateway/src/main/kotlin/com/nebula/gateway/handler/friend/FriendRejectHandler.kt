package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 拒绝好友申请 Handler。
 *
 * 委托 FriendService 处理拒绝申请业务逻辑。
 *
 * @param friendService 好友业务服务
 */
class FriendRejectHandler(
    private val friendService: FriendService
) : Handler<FriendRejectReq, Response> {

    override val method: String = "friend/reject"

    override suspend fun handle(req: FriendRejectReq): Response {
        val session = currentCoroutineContext().requireSession()
        friendService.rejectFriendRequest(req, session.userId)

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
