package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchGetUserResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.UserService

/**
 * 批量查询用户信息 Handler — method = "user/batchGet"。
 *
 * 委托 UserService 批量查询用户信息。
 *
 * @param userService 用户业务服务
 */
class BatchGetUserHandler(
    private val userService: UserService
) : Handler<BatchIdRequest, BatchGetUserResp> {

    override val method: String = "user/batchGet"

    override suspend fun handle(req: BatchIdRequest): BatchGetUserResp {
        return userService.batchGetUsers(req)
    }
}
