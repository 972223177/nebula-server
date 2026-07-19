package com.nebula.gateway.service

import com.nebula.chat.PushEventType
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
import com.nebula.service.user.UserPrivacyService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友在线状态变更广播器（D-50, D-57）。
 *
 * 从 ChatService.pushStatusChangeToFriends 抽离，封装"查询好友列表 → 过滤隐藏用户 → 逐个推送 STATUS_CHANGED"逻辑。
 * 通过注入的 serverScope 在后台协程执行，连接断开不影响推送（D-85）。
 *
 * 设计决策：
 * - serverScope 由 ChatService 统管生命周期，本组件不持有 connectionScope，守住"连接态不泄漏到无状态组件"边界。
 * - 推送失败逐条 try-catch，单条失败不阻断其余好友推送。
 *
 * @param serverScope 服务级后台作用域（跨连接存活）
 * @param friendService 好友服务（查询好友列表）
 * @param privacyService 隐私设置服务（过滤隐藏在线状态的用户）
 * @param pushService 推送服务
 */
class FriendStatusNotifier(
    private val serverScope: CoroutineScope,
    private val friendService: FriendService,
    private val privacyService: UserPrivacyService,
    private val pushService: PushService,
) {
    private companion object {
        val logger = KotlinLogging.logger {}
    }

    /**
     * 推送状态变更给所有在线好友（D-50, D-57）。
     *
     * 查询好友列表 → 过滤隐藏用户 → 逐个 pushEventToUser(STATUS_CHANGED)。
     * JPA 查询在 withContext(Dispatchers.IO) 中执行。
     *
     * @param userId 状态变更用户 UID
     * @param status 新状态：0=离线 1=在线 2=隐藏
     */
    fun notifyFriends(userId: Long, status: Int) {
        serverScope.launch {
            try {
                val friendships = withContext(Dispatchers.IO) {
                    friendService.findFriendsByUserId(userId)
                }
                val friendUids = friendships.map { f ->
                    if (f.userId == userId) f.friendId else f.userId
                }.distinct()

                // 过滤隐藏用户
                val hiddenUids = privacyService.batchGetHideOnlineStatus(friendUids)
                val visibleFriends = friendUids.filter { it !in hiddenUids }

                val payload = StatusChangedPayload.newBuilder()
                    .setUid(userId)
                    .setStatus(status)
                    .build()

                visibleFriends.forEach { friendUid ->
                    try {
                        pushService.pushEventToUser(
                            friendUid, PushEventType.STATUS_CHANGED, payload.toByteString()
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to push status change to friend=$friendUid" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "notifyFriends failed for userId=$userId" }
            }
        }
    }
}
