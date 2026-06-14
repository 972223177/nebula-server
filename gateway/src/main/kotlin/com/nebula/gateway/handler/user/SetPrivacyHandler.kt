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
import com.nebula.service.user.UserPrivacyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在线状态可见性设置 Handler — method = "user/setPrivacy"（BIZ-USER-05, D-09, D-11, D-57）。
 *
 * 职责：
 * - 委托 UserPrivacyService 处理隐私设置业务逻辑
 * - 推送状态变更给所有在线好友（gateway 层职责）
 *
 * @param userPrivacyService 用户隐私设置业务服务
 * @param pushService 推送服务
 * @param friendshipRepository 好友关系仓库
 * @param privacyRepository 隐私设置缓存
 */
class SetPrivacyHandler(
    private val userPrivacyService: UserPrivacyService,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val pushService: PushService,
    private val friendshipRepository: FriendshipRepository
) : Handler<SetPrivacyReq, Response> {

    /** 推送专用协程作用域（IO 调度器 + SupervisorJob），fire-and-forget 模式 */
    private val pushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val method: String = "user/setPrivacy"

    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId

        // 委托 UserPrivacyService 处理业务逻辑
        userPrivacyService.setHideOnlineStatus(userId, req)

        // D-57: 切换隐藏状态时同步更新 OnlineStatusRepository
        val newStatus = if (req.hideOnlineStatus) {
            onlineStatusRepository.setHidden(userId)
            2
        } else {
            onlineStatusRepository.setOnline(userId)
            1
        }

        // D-50: 推送状态变更给所有在线好友（fire-and-forget，gateway 层职责）
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

                val payload = StatusChangedPayload.newBuilder()
                    .setUid(userId)
                    .setStatus(newStatus)
                    .build()

                friendUids.forEach { friendUid ->
                    try {
                        pushService.pushEventToUser(
                            friendUid, PushEventType.STATUS_CHANGED, payload.toByteString()
                        )
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
