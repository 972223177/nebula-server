package com.nebula.service.user

import com.nebula.repository.redis.OnlineStatusRepository

/**
 * 用户在线状态服务 — 封装 OnlineStatusRepository 的在线状态操作。
 *
 * 作为 service 层对外接口，屏蔽底层 Redis 实现细节。
 *
 * @param onlineStatusRepository 在线状态 Redis 操作接口
 */
class OnlineStatusService(
    private val onlineStatusRepository: OnlineStatusRepository
) {

    /**
     * 获取用户在线状态。
     *
     * 返回 [OnlineStatusInfo] 替代在 gateway 层直接暴露 repository 层的 [com.nebula.repository.redis.OnlineStatusData]，
     * 仅包含 gateway 层需要的 status 字段。
     *
     * @param uid 用户 ID
     * @return 在线状态信息 DTO，key 不存在返回 null
     */
    suspend fun getStatus(uid: Long): OnlineStatusInfo? {
        val data = onlineStatusRepository.getStatus(uid)
        return data?.let { OnlineStatusInfo(status = it.status) }
    }

    /**
     * 标记用户在线（status=1）。
     *
     * @param uid 用户 ID
     */
    suspend fun setOnline(uid: Long) {
        onlineStatusRepository.setOnline(uid)
    }

    /**
     * 标记用户离线。
     *
     * @param uid 用户 ID
     */
    suspend fun setOffline(uid: Long) {
        onlineStatusRepository.setOffline(uid)
    }

    /**
     * 刷新用户在线状态的 TTL。
     *
     * @param uid 用户 ID
     */
    suspend fun refreshTtl(uid: Long) {
        onlineStatusRepository.refreshTtl(uid)
    }

    /**
     * 标记用户在线状态为隐藏（status=2）。
     *
     * @param uid 用户 ID
     */
    suspend fun setHidden(uid: Long) {
        onlineStatusRepository.setHidden(uid)
    }
}
