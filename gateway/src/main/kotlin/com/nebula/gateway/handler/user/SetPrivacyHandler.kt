package com.nebula.gateway.handler.user

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.friend.FriendService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 在线状态可见性设置 Handler — method = "user/setPrivacy"（BIZ-USER-05, D-09, D-11, D-57）。
 *
 * 职责：
 * - 委托 UserPrivacyService 处理隐私设置业务逻辑
 * - 推送状态变更给所有在线好友（gateway 层职责）
 *
 * @param userPrivacyService 用户隐私设置业务服务
 * @param onlineStatusService 用户在线状态服务
 * @param pushService 推送服务
 * @param friendService 好友业务服务
 * @param pushScope 协程作用域（fire-and-forget 推送，由 Koin 管理的 sendHandlerScope 注入）
 */
class SetPrivacyHandler(
    private val userPrivacyService: UserPrivacyService,
    private val onlineStatusService: OnlineStatusService,
    private val pushService: PushService,
    private val friendService: FriendService,
    private val pushScope: CoroutineScope
) : Handler<SetPrivacyReq, Response> {

    override val method: String = "user/setPrivacy"

    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId

        // 委托 UserPrivacyService 处理业务逻辑
        userPrivacyService.setHideOnlineStatus(userId, req)

        // D-57: 切换隐藏状态时同步更新在线状态服务
        val newStatus = if (req.hideOnlineStatus) {
            onlineStatusService.setHidden(userId)
            2
        } else {
            onlineStatusService.setOnline(userId)
            1
        }

        // D-50: 推送状态变更给所有在线好友（fire-and-forget，gateway 层职责）
        pushScope.launch {
            try {
                val friendships = friendService.findFriendsByUserId(userId)
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
                    } catch (e: Exception) {
                        logger.error(e) { "推送状态变更给好友失败: userId=$userId, friendUid=$friendUid" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "查询好友列表或构建推送载荷失败: userId=$userId" }
            }
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
    }
}
