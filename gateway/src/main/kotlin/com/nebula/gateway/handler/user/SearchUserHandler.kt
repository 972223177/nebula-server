package com.nebula.gateway.handler.user

import com.nebula.chat.user.SearchUserReq
import com.nebula.chat.user.SearchUserResp
import com.nebula.chat.user.UserBrief
import com.nebula.gateway.handler.Handler
import com.nebula.repository.repository.UserRepository
import java.time.ZoneOffset

/**
 * 用户搜索 Handler — method = "user/search"（BIZ-USER-01, D-07, D-08）。
 *
 * 职责：
 * - 按用户名进行 LIKE %keyword% 模糊搜索（D-07）
 * - 支持游标分页，每页最多 20 条，按注册时间（createdAt）倒序排列（D-08）
 * - 游标策略：首次 cursor=0，服务端取 limit+1 条判断 has_more
 *
 * 安全设计（T-05-07）：
 * - 搜索通过 UserRepository.findByUsernameContaining() 的 JPA @Query 参数绑定实现，
 *   使用 :keyword 占位符而非字符串拼接，防止 SQL 注入
 * - 关键词为空时返回空结果，不进行模糊搜索
 *
 * 设计决策引用：
 * - D-07: 搜索范围仅 username 字段，LIKE %keyword% 模糊匹配
 * - D-08: 游标分页，按 createdAt 倒序，每页最多 20 条
 *
 * @param userRepository 用户数据仓库
 */
class SearchUserHandler(
    private val userRepository: UserRepository
) : Handler<SearchUserReq, SearchUserResp> {

    override val method: String = "user/search"

    /** 单页最大返回条数（D-08） */
    private val maxLimit = 20

    override suspend fun handle(req: SearchUserReq): SearchUserResp {
        val keyword = req.keyword.trim()

        // 空关键词返回空结果
        if (keyword.isBlank()) {
            return SearchUserResp.getDefaultInstance()
        }

        val cursor = req.cursor
        val limit = if (req.limit in 1..maxLimit) req.limit else maxLimit

        // 多取一条判断是否有更多数据（D-08 游标分页策略）
        val users = userRepository.findByUsernameContaining(
            keyword = keyword,
            cursor = cursor,
            limit = limit + 1
        )
        val hasMore = users.size > limit
        val result = if (hasMore) users.dropLast(1) else users

        val builder = SearchUserResp.newBuilder()
        result.forEach { entity ->
            builder.addUsers(UserBrief.newBuilder()
                .setUid(entity.id!!)
                .setUsername(entity.username)
                .setDisplayName(entity.nickname)
                .setAvatarUrl(entity.avatar)
                .setCreatedAt(entity.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .build())
        }
        builder.setNextCursor(
            result.lastOrNull()?.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0
        )
        builder.setHasMore(hasMore)
        return builder.build()
    }
}
