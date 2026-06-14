package com.nebula.service.user

import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository

/**
 * 用户隐私设置业务服务（D-09, D-11, D-57）。
 *
 * 提供在线状态可见性的设置和查询。
 * Redis + MySQL 双写策略：Redis 实时生效，MySQL 异步持久化。
 */
class UserPrivacyService(
    private val privacyRepository: PrivacyRepository,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val friendshipRepository: FriendshipRepository
) {

    /**
     * 设置在线状态可见性。
     *
     * 更新 Redis 隐私标记后同步调整在线状态 Redis Key，
     * 隐藏时标记为 hidden，恢复时标记为 online。
     *
     * @param userId 当前用户 ID
     * @param req 设置请求（含 hideOnlineStatus）
     */
    suspend fun setHideOnlineStatus(userId: Long, req: SetPrivacyReq) {
        val hide = req.hideOnlineStatus
        privacyRepository.setHideOnlineStatus(userId, hide)

        if (hide) {
            // 隐藏在线状态时，同步标记 Redis 中的在线状态为隐藏
            onlineStatusRepository.setHidden(userId)
        } else {
            // 恢复可见时，标记为在线
            onlineStatusRepository.setOnline(userId)
        }
    }

    /**
     * 查询在线状态可见性。
     *
     * @param userId 当前用户 ID
     * @param req 查询请求
     * @return 隐私设置响应
     */
    suspend fun getHideOnlineStatus(userId: Long, req: GetPrivacyReq): GetPrivacyResp {
        val hide = privacyRepository.getHideOnlineStatus(userId)
        return GetPrivacyResp.newBuilder().setHideOnlineStatus(hide).build()
    }
}
