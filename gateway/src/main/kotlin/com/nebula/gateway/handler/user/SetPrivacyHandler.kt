package com.nebula.gateway.handler.user

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
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
 * @param serverScope 服务级后台作用域，用于 fire-and-forget 推送，独立于请求上下文（不受 10s 超时/断连取消）
 *
 * 2026-07 review P1 修复：DB 写入（setHideOnlineStatus / setFriendApprovalMode / 在线状态切换）仍内联，
 * 必须成功才返回；仅"查好友 + 推送状态变更"改为挂到 serverScope 的 fire-and-forget，
 * 与响应解耦，且不被 Dispatcher 的 10s withTimeout 取消链波及（见 D-85 重构回归）。
 */
class SetPrivacyHandler(
    private val userPrivacyService: UserPrivacyService,
    private val onlineStatusService: OnlineStatusService,
    private val pushService: PushService,
    private val friendService: FriendService,
    private val serverScope: CoroutineScope
) : Handler<SetPrivacyReq, Response> {

    override val method: String = "user/setPrivacy"

    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId

        // 处理在线状态隐藏设置
        userPrivacyService.setHideOnlineStatus(userId, req)

        // 处理好友申请通过模式设置（仅当客户端传入时才修改）
        if (req.hasFriendApproval()) {
            userPrivacyService.setFriendApprovalMode(userId, req.friendApproval)
        }

        // D-57: 切换隐藏状态时同步更新在线状态服务
        val newStatus = if (req.hideOnlineStatus) {
            onlineStatusService.setHidden(userId)
            2
        } else {
            onlineStatusService.setOnline(userId)
            1
        }

        // D-50: 推送状态变更给所有在线好友。
        // 2026-07 review P1：查好友 + 推送挂到 serverScope 做 fire-and-forget，
        // 与响应解耦，不受 Dispatcher 10s 超时 / 连接断开影响（保证收件人收到推送）。
        serverScope.launch {
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
