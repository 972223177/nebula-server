package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendCheckRelationReq
import com.nebula.chat.friend.FriendCheckRelationResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 查询两个用户间关系状态的 Handler（friend/check）。
 *
 * 返回关系状态：FRIEND / PENDING_SENT(我申请了对方) / PENDING_RECEIVED(对方申请了我) / REJECTED / NONE。
 * 非 NONE/FRIEND 时附带关联的申请 requestId，方便客户端直接调用 friend/accept 或 friend/reject。
 *
 * @param friendService 好友业务服务
 */
class FriendCheckRelationHandler(
    private val friendService: FriendService
) : Handler<FriendCheckRelationReq, FriendCheckRelationResp> {

    override val method: String = "friend/check"

    override suspend fun handle(req: FriendCheckRelationReq): FriendCheckRelationResp {
        val session = currentCoroutineContext().requireSession()
        val (status, requestId) = friendService.checkRelation(session.userId, req.uid)

        return FriendCheckRelationResp.newBuilder()
            .setStatus(status)
            .also { builder ->
                requestId?.let { builder.requestId = it }
            }
            .build()
    }
}
