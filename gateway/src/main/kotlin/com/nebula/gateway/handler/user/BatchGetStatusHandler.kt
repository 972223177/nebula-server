package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchGetStatusResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.UserOnlineStatus
import com.nebula.gateway.handler.Handler
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository

/**
 * 批量在线状态查询 Handler — method = "user/batchGetStatus"（BIZ-USER-04, D-10）。
 *
 * 根据用户 ID 列表批量查询在线状态。
 *
 * **隐私过滤（D-10）：**
 * 使用 [PrivacyRepository.batchGetHideOnlineStatus()]（Redis MGET）一次批量查询所有用户的隐私设置，
 * hideOnlineStatus=true 的用户跳过，不在结果中返回。
 *
 * **Review 修复（N+1 → MGET）：**
 * 原方案对每个用户单独调用 getHideOnlineStatus(uid) 产生 N 次 Redis GET。
 * 当前方案使用 Redis MGET 一次查询所有用户的隐私设置，避免 N+1 性能问题。
 *
 * @param onlineStatusRepository 用户在线状态缓存操作
 * @param privacyRepository 用户隐私设置缓存操作，用于批量查询隐藏在线状态的用户
 */
class BatchGetStatusHandler(
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) : Handler<BatchIdRequest, BatchGetStatusResp> {

    /** method 路由：user/batchGetStatus — 批量在线状态查询 */
    override val method: String = "user/batchGetStatus"

    /**
     * 批量查询用户的在线状态。
     *
     * 先使用 MGET 批量查询所有用户的 hideOnlineStatus 设置，过滤掉隐藏用户后，
     * 逐用户查询在线状态（isOnline 为单 key 查询，无法批量优化）。
     *
     * @param req 包含用户 ID 列表的请求
     * @return 在线状态列表，隐藏用户不在结果中返回
     */
    override suspend fun handle(req: BatchIdRequest): BatchGetStatusResp {
        // Review 修复：使用 batchGetHideOnlineStatus() 一次 MGET 批量查询
        // 原方案对每个用户单独调用 getHideOnlineStatus(uid) 产生 N 次 Redis GET
        // 新方案使用 Redis MGET 一次查询所有用户的隐私设置
        val hiddenUserIds = privacyRepository.batchGetHideOnlineStatus(req.uidsList)

        val builder = BatchGetStatusResp.newBuilder()
        for (uid in req.uidsList) {
            if (uid in hiddenUserIds) continue  // D-10: 跳过隐藏用户
            val isOnline = onlineStatusRepository.isOnline(uid)
            builder.addStatuses(UserOnlineStatus.newBuilder()
                .setUid(uid)
                .setStatus(if (isOnline) 1 else 0)  // 1=在线, 0=离线
                .build())
        }
        return builder.build()
    }
}
