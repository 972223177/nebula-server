package com.nebula.service.user

import com.nebula.repository.redis.OnlineStatusRepository

/**
 * 用户在线状态服务契约（2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 所有公开方法声明在此接口；[OnlineStatusServiceImpl] 实现之，聚合 Facade [OnlineStatusService] 经 `by` 委托暴露。
 */
interface OnlineStatusOperations {
    /** 获取用户在线状态 */
    suspend fun getStatus(uid: Long): OnlineStatusInfo?

    /** 标记用户在线（status=1） */
    suspend fun setOnline(uid: Long)

    /** 标记用户离线 */
    suspend fun setOffline(uid: Long)

    /** 刷新用户在线状态的 TTL */
    suspend fun refreshTtl(uid: Long)

    /** 标记用户在线状态为隐藏（status=2） */
    suspend fun setHidden(uid: Long)
}

/**
 * 用户在线状态服务实现 — 封装 OnlineStatusRepository 的在线状态操作。
 *
 * 作为 service 层对外接口，屏蔽底层 Redis 实现细节。
 *
 * @param onlineStatusRepository 在线状态 Redis 操作接口
 */
class OnlineStatusServiceImpl(
    private val onlineStatusRepository: OnlineStatusRepository
) : OnlineStatusOperations {

    /**
     * 获取用户在线状态。
     *
     * 返回 [OnlineStatusInfo] 替代在 gateway 层直接暴露 repository 层的 [com.nebula.repository.redis.OnlineStatusData]，
     * 仅包含 gateway 层需要的 status 字段。
     *
     * @param uid 用户 ID
     * @return 在线状态信息 DTO，key 不存在返回 null
     */
    override suspend fun getStatus(uid: Long): OnlineStatusInfo? {
        val data = onlineStatusRepository.getStatus(uid)
        return data?.let { OnlineStatusInfo(status = it.status) }
    }

    /**
     * 标记用户在线（status=1）。
     *
     * @param uid 用户 ID
     */
    override suspend fun setOnline(uid: Long) {
        onlineStatusRepository.setOnline(uid)
    }

    /**
     * 标记用户离线。
     *
     * @param uid 用户 ID
     */
    override suspend fun setOffline(uid: Long) {
        onlineStatusRepository.setOffline(uid)
    }

    /**
     * 刷新用户在线状态的 TTL。
     *
     * @param uid 用户 ID
     */
    override suspend fun refreshTtl(uid: Long) {
        onlineStatusRepository.refreshTtl(uid)
    }

    /**
     * 标记用户在线状态为隐藏（status=2）。
     *
     * @param uid 用户 ID
     */
    override suspend fun setHidden(uid: Long) {
        onlineStatusRepository.setHidden(uid)
    }
}
