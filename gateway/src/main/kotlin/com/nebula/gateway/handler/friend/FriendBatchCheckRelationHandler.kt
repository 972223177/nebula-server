package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendBatchCheckRelationReq
import com.nebula.chat.friend.FriendBatchCheckRelationResp
import com.nebula.chat.friend.FriendRelationItem
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.friend.FriendService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 批量查询当前用户与多个目标用户关系状态的 Handler（friend/batchCheck）。
 *
 * 与 friend/check 的区别：一次请求传入多个 uid，返回每个 uid 的关系状态。
 * 适用于客户端启动时批量加载用户列表的关系标记（如联系人页面）。
 *
 * @param friendService 好友业务服务
 */
class FriendBatchCheckRelationHandler(
    private val friendService: FriendService
) : Handler<FriendBatchCheckRelationReq, FriendBatchCheckRelationResp> {

    override val method: String = "friend/batchCheck"

    override suspend fun handle(req: FriendBatchCheckRelationReq): FriendBatchCheckRelationResp {
        val session = currentCoroutineContext().requireSession()
        val results = friendService.batchCheckRelation(session.userId, req.uidsList)

        val items = results.map { r ->
            FriendRelationItem.newBuilder()
                .setUid(r.uid)
                .setStatus(r.status)
                .also { builder ->
                    r.requestId?.let { builder.requestId = it }
                }
                .build()
        }

        return FriendBatchCheckRelationResp.newBuilder()
            .addAllItems(items)
            .build()
    }
}
