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
 * @param onlineStatusRepository 用户在线状态缓存操作
 * @param privacyRepository 用户隐私设置缓存操作
 */
class BatchGetStatusHandler(
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) : Handler<BatchIdRequest, BatchGetStatusResp> {

    override val method: String = "user/batchGetStatus"

    override suspend fun handle(req: BatchIdRequest): BatchGetStatusResp {
        val hiddenUserIds = privacyRepository.batchGetHideOnlineStatus(req.uidsList)

        val builder = BatchGetStatusResp.newBuilder()
        for (uid in req.uidsList) {
            if (uid in hiddenUserIds) continue

            val statusData = onlineStatusRepository.getStatus(uid)
            val status = statusData?.status ?: 0

            builder.addStatuses(UserOnlineStatus.newBuilder()
                .setUid(uid)
                .setStatus(status)
                .build())
        }
        return builder.build()
    }
}
