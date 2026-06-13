package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendDeleteReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.repository.FriendshipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * 删除好友 Handler（软删除）。
 *
 * 排序 uid 后查找好友记录 → 校验 deleted=0 → 软删除 friendship.deleted=1
 * → 返回 Response(OK)。会话保留，不受影响。
 */
class FriendDeleteHandler(
    private val friendshipRepository: FriendshipRepository
) : Handler<FriendDeleteReq, Response> {

    override val method: String = "friend/delete"

    override suspend fun handle(req: FriendDeleteReq): Response {
        val session = currentCoroutineContext().requireSession()
        val targetUid = req.uid

        // 排序 uid 确保查找一致性（userId 是 smaller，friendId 是 larger）
        val smaller = minOf(session.userId, targetUid)
        val larger = maxOf(session.userId, targetUid)

        withContext(Dispatchers.IO) {
            val friendship = friendshipRepository.findByUserIdAndFriendId(smaller, larger)
                ?: throw FriendException(BizCode.FRIEND_NOT_FOUND)

            // 校验是否已被删除
            if (friendship.deleted == 1) {
                throw FriendException(BizCode.FRIEND_NOT_FOUND)
            }

            // 软删除
            friendship.deleted = 1
            friendshipRepository.save(friendship)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
