package com.nebula.repository.repository

import com.nebula.repository.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 用户数据仓库。
 */
interface UserRepository : JpaRepository<UserEntity, Long> {
    /** 按用户名查找用户 */
    fun findByUsername(username: String): UserEntity?

    /**
     * 按用户名模糊搜索，支持游标分页（D-07, D-08）。
     *
     * 使用 JPA @Query 参数绑定（:keyword/:cursor）防止 SQL 注入（T-05-07 缓解措施）。
     * cursor=0 时忽略游标条件（首次查询），后续查询按 createdAt 倒序游标推进。
     * 搜索结果按 createdAt DESC 排序，确保分页顺序一致。
     *
     * @param keyword 搜索关键词（LIKE %keyword%）
     * @param cursor 游标（createdAt 毫秒时间戳），0 表示首次查询
     * @param limit 返回行数限制（由调用方传入 limit+1 用于判断 hasMore）
     * @return 匹配的用户列表，按 createdAt 倒序
     */
    @Query("SELECT u FROM UserEntity u WHERE u.username LIKE %:keyword% AND (:cursor = 0L OR u.createdAt < :cursor) ORDER BY u.createdAt DESC")
    fun findByUsernameContaining(
        @Param("keyword") keyword: String,
        @Param("cursor") cursor: Long,
        limit: Int
    ): List<UserEntity>
}
