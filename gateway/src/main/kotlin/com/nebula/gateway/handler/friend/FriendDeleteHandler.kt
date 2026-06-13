package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendDeleteReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 删除好友 Handler（软删除）。
 *
 * 委托 FriendService 处理删除好友业务逻辑。
 *
 * @param friendService 好友业务服务
 */
class FriendDeleteHandler(
    private val friendService: FriendService
) : Handler<FriendDeleteReq, Response> {

    override val method: String = "friend/delete"

    override suspend fun handle(req: FriendDeleteReq): Response {
        val session = currentCoroutineContext().requireSession()
        friendService.deleteFriend(req, session.userId)

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
