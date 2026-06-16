package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchGetStatusResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.UserOnlineStatus
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService

/**
 * 批量在线状态查询 Handler — method = "user/batchGetStatus"（BIZ-USER-04, D-10, D-57）。
 *
 * @param onlineStatusService 用户在线状态服务
 * @param privacyService 用户隐私设置服务
 */
class BatchGetStatusHandler(
    private val onlineStatusService: OnlineStatusService,
    private val privacyService: UserPrivacyService
) : Handler<BatchIdRequest, BatchGetStatusResp> {

    override val method: String = "user/batchGetStatus"

    override suspend fun handle(req: BatchIdRequest): BatchGetStatusResp {
        val hiddenUserIds = privacyService.batchGetHideOnlineStatus(req.uidsList)

        val builder = BatchGetStatusResp.newBuilder()
        for (uid in req.uidsList) {
            if (uid in hiddenUserIds) continue

            val statusData = onlineStatusService.getStatus(uid)
            val status = statusData?.status ?: 0

            builder.addStatuses(UserOnlineStatus.newBuilder()
                .setUid(uid)
                .setStatus(status)
                .build())
        }
        return builder.build()
    }
}
