package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchGetUserResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.UserBrief
import com.nebula.gateway.handler.Handler
import com.nebula.repository.repository.UserRepository

/**
 * 批量用户摘要查询 Handler — method = "user/batchGet"（BIZ-USER-03）。
 *
 * 根据用户 ID 列表批量查询简要信息。
 *
 * **缺失 ID 静默跳过（设计权衡）：**
 * `JpaRepository.findAllById()` 对数据库中不存在的 ID 静默跳过，不会返回对应条目。
 * 这是 JPA 的预期行为，目的为实现低延迟批量查询，不因无效 ID 中断。
 * 客户端应通过结果中缺失的 UID 判断该用户不存在。
 *
 * @param userRepository 用户数据仓库，用于按 ID 列表批量查询
 */
class BatchGetUserHandler(
    private val userRepository: UserRepository
) : Handler<BatchIdRequest, BatchGetUserResp> {

    /** method 路由：user/batchGet — 批量用户摘要 */
    override val method: String = "user/batchGet"

    /**
     * 批量查询用户简要信息。
     *
     * @param req 包含用户 ID 列表的请求
     * @return 用户简要信息列表，缺失 ID 对应的用户不会出现在返回结果中
     */
    override suspend fun handle(req: BatchIdRequest): BatchGetUserResp {
        val users = userRepository.findAllById(req.uidsList)
        val builder = BatchGetUserResp.newBuilder()

        users.forEach { entity ->
            builder.addUsers(UserBrief.newBuilder()
                .setUid(entity.id!!)
                .setUsername(entity.username)
                .setDisplayName(entity.nickname)
                .setAvatarUrl(entity.avatar)
                .build())
        }
        return builder.build()
    }
}
