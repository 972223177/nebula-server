package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchGetStatusResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.UserOnlineStatus
import com.nebula.gateway.handler.Handler
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository

/**
 * 批量在线状态查询 Handler — method = "user/batchGetStatus"（BIZ-USER-04, D-10, D-57）。
 *
 * 根据用户 ID 列表批量查询在线状态，返回三值状态（0=离线/1=在线/2=隐藏）。
 *
 * **隐私过滤（D-10）：**
 * 使用 [PrivacyRepository.batchGetHideOnlineStatus()]（Redis MGET）一次批量查询所有用户的隐私设置，
 * hideOnlineStatus=true 的用户跳过，不在结果中返回。
 *
 * **三值状态适配（D-57）：**
 * 使用 [OnlineStatusRepository.getStatus()] 替代旧的 isOnline()，
 * 返回 0=离线/1=在线/2=隐藏（隐藏用户已在隐私过滤阶段跳过，此处 status=2 为安全兜底）。
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
     * 批量查询用户的在线状态（三值：0/1/2）。
     *
     * 先使用 MGET 批量查询隐私设置过滤隐藏用户，
     * 再逐个查询在线状态（D-57 三值适配）。
     *
     * @param req 包含用户 ID 列表的请求
     * @return 在线状态列表，隐藏用户不在结果中返回
     */
    override suspend fun handle(req: BatchIdRequest): BatchGetStatusResp {
        val hiddenUserIds = privacyRepository.batchGetHideOnlineStatus(req.uidsList)

        val builder = BatchGetStatusResp.newBuilder()
        for (uid in req.uidsList) {
            if (uid in hiddenUserIds) continue  // D-10: 跳过隐藏用户

            // D-57: 使用 getStatus 获取三值状态，替代旧 isOnline 二值
            val statusData = onlineStatusRepository.getStatus(uid)
            val status = statusData?.status ?: 0  // 0=离线, 1=在线, 2=隐藏（兜底）

            builder.addStatuses(UserOnlineStatus.newBuilder()
                .setUid(uid)
                .setStatus(status)
                .build())
        }
        return builder.build()
    }
}
