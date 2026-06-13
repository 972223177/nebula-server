package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.repository.FriendRequestRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * 拒绝好友申请 Handler。
 *
 * 加载 FriendRequestEntity → 校验 status=pending + toUid=session.userId
 * → 更新 status=2(rejected) → 返回 Response(OK)。
 */
class FriendRejectHandler(
    private val friendRequestRepository: FriendRequestRepository
) : Handler<FriendRejectReq, Response> {

    override val method: String = "friend/reject"

    override suspend fun handle(req: FriendRejectReq): Response {
        val session = currentCoroutineContext().requireSession()

        withContext(Dispatchers.IO) {
            val request = friendRequestRepository.findById(req.requestId)
                .orElseThrow { FriendException(BizCode.REQUEST_NOT_FOUND) }

            // 校验接收方是否为当前用户
            if (request.toUid != session.userId) {
                throw FriendException(BizCode.FORBIDDEN, "只能处理自己的好友申请")
            }

            // 校验申请状态是否可处理（0=pending 才可拒绝）
            if (request.status != 0) {
                throw FriendException(BizCode.REQUEST_HANDLED)
            }

            // 更新状态为 rejected
            request.status = 2
            friendRequestRepository.save(request)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
