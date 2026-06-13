package com.nebula.gateway.handler.user

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在线状态可见性设置 Handler — method = "user/setPrivacy"（BIZ-USER-05, D-09, D-11, D-57）。
 *
 * 设置当前用户的在线状态是否对其他人可见。
 * 切换隐藏状态时同步更新 OnlineStatusRepository（D-57），
 * 并推送状态变更给所有在线好友（D-50）。
 *
 * **写入策略（D-09/D-11）：**
 * [PrivacyRepository.setHideOnlineStatus()] 先写 Redis（立即生效），
 * 再异步 CoroutineScope(Dispatchers.IO).launch 刷 MySQL（best-effort 模式）。
 *
 * **安全约束（T-05-10）：**
 * 只允许修改当前登录用户的隐私设置。
 *
 * @param privacyRepository 用户隐私设置缓存操作
 * @param onlineStatusRepository 在线状态 Redis 操作（D-57）
 * @param pushService 推送服务
 * @param friendshipRepository 好友关系仓库（查询好友列表用于推送）
 */
class SetPrivacyHandler(
    private val privacyRepository: PrivacyRepository,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val pushService: PushService,
    private val friendshipRepository: FriendshipRepository
) : Handler<SetPrivacyReq, Response> {

    /** 用于推送好友状态变更的协程作用域 */
    private val pushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** method 路由：user/setPrivacy — 设置在线状态可见性 */
    override val method: String = "user/setPrivacy"

    /**
     * 设置当前用户的在线状态可见性，同步更新 Redis 状态并推送好友（D-57）。
     *
     * @param req 包含 hideOnlineStatus 布尔值的请求
     * @return 通用响应，仅包含状态码
     */
    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId
        privacyRepository.setHideOnlineStatus(userId, req.hideOnlineStatus)

        // D-57: 切换隐藏状态时同步更新 OnlineStatusRepository
        val newStatus = if (req.hideOnlineStatus) {
            onlineStatusRepository.setHidden(userId)
            2  // 隐藏状态
        } else {
            onlineStatusRepository.setOnline(userId)
            1  // 在线状态
        }

        // D-50: 推送状态变更给所有在线好友（fire-and-forget）
        pushScope.launch {
            try {
                val friendships = withContext(Dispatchers.IO) {
                    friendshipRepository.findFriendsByUserId(
                        userId, 0, org.springframework.data.domain.PageRequest.of(0, Int.MAX_VALUE)
                    )
                }
                val friendUids = friendships.map { f ->
                    if (f.userId == userId) f.friendId else f.userId
                }.distinct()

                // 过滤隐藏用户
                val hiddenUids = privacyRepository.batchGetHideOnlineStatus(friendUids)
                val visibleFriends = friendUids.filter { it !in hiddenUids }

                val payload = StatusChangedPayload.newBuilder()
                    .setUid(userId)
                    .setStatus(newStatus)
                    .build()

                visibleFriends.forEach { friendUid ->
                    try {
                        pushService.pushEventToUser(
                            friendUid, PushEventType.STATUS_CHANGED, payload.toByteString()
                        )
                    } catch (e: Exception) {
                        // 单个好友推送失败不影响其他好友
                    }
                }
            } catch (e: Exception) {
                // 整体推送失败不阻塞主流程
            }
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
