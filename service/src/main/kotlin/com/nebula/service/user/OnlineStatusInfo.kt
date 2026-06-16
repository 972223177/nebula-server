package com.nebula.service.user

/**
 * 在线状态信息 DTO — 供 gateway 层查询用户在线状态，替代 [com.nebula.repository.redis.OnlineStatusData]。
 *
 * 屏蔽 repository 层的 Redis 数据结构细节，仅暴露 gateway 层需要的业务字段。
 *
 * @param status 在线状态：0=离线 1=在线 2=隐藏（D-57）
 */
data class OnlineStatusInfo(
    val status: Int
)
