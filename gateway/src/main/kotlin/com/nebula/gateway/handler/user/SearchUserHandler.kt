package com.nebula.gateway.handler.user

import com.nebula.chat.user.SearchUserReq
import com.nebula.chat.user.SearchUserResp
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.UserService

/**
 * 用户搜索 Handler — method = "user/search"（BIZ-USER-01, D-07, D-08）。
 *
 * 职责：
 * - 委托 UserService 处理搜索逻辑（游标分页、模糊搜索）
 *
 * @param userService 用户业务服务
 */
class SearchUserHandler(
    private val userService: UserService
) : Handler<SearchUserReq, SearchUserResp> {

    override val method: String = "user/search"

    override suspend fun handle(req: SearchUserReq): SearchUserResp {
        return userService.searchUsers(
            keyword = req.keyword,
            cursor = req.cursor,
            limit = req.limit
        )
    }
}
