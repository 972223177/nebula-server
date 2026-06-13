package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 获取好友列表 Handler，支持游标分页（D-46）。
 *
 * 委托 FriendService 查询好友列表。
 *
 * @param friendService 好友业务服务
 */
class FriendListHandler(
    private val friendService: FriendService
) : Handler<FriendListReq, FriendListResp> {

    override val method: String = "friend/list"

    override suspend fun handle(req: FriendListReq): FriendListResp {
        val session = currentCoroutineContext().requireSession()
        return friendService.listFriends(req, session.userId)
    }
}
