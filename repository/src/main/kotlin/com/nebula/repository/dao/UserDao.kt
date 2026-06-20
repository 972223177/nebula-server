package com.nebula.repository.dao

import com.nebula.repository.entity.UserEntity
import jakarta.persistence.EntityManager
import java.time.LocalDateTime

/**
 * 用户数据访问对象（替代原 UserRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 */
class UserDao : EntityDao<UserEntity>(UserEntity::class.java) {

    /**
     * 按用户名精确查找用户。
     *
     * @param em 当前事务的 [EntityManager]
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    suspend fun findByUsername(em: EntityManager, username: String): UserEntity? =
        querySingle(
            em,
            "SELECT u FROM UserEntity u WHERE u.username = :username",
            "username" to username
        )

    /**
     * 按用户名模糊搜索，支持游标分页（D-07, D-08）。
     *
     * 使用 JPQL 参数绑定防止 SQL 注入（T-05-07 缓解措施）。
     * cursor 为 null 时忽略游标条件（首次查询），后续查询按 createdAt 倒序游标推进。
     *
     * @param em 当前事务的 [EntityManager]
     * @param keyword 搜索关键词（LIKE %keyword%）
     * @param cursor 游标（createdAt LocalDateTime），null 表示首次查询
     * @param limit 返回行数限制
     * @return 匹配的用户列表，按 createdAt 倒序
     */
    suspend fun findByUsernameContaining(
        em: EntityManager,
        keyword: String,
        cursor: LocalDateTime?,
        limit: Int
    ): List<UserEntity> {
        val query = em.createQuery(
            "SELECT u FROM UserEntity u " +
                "WHERE u.username LIKE :keyword " +
                "AND (:cursor IS NULL OR u.createdAt < :cursor) " +
                "ORDER BY u.createdAt DESC",
            UserEntity::class.java
        )
        query.setParameter("keyword", "%$keyword%")
        query.setParameter("cursor", cursor)
        query.maxResults = limit
        return query.resultList
    }

    /**
     * 按用户名模糊搜索，使用 Snowflake id 作为游标（Phase 5.1 改进）。
     *
     * 比 createdAt 游标更稳定：id 唯一、单调递增，相同毫秒的多条记录不会跳数据。
     * 配合二级排序（id DESC），保证游标推进时不漏。
     *
     * @param em 当前事务的 [EntityManager]
     * @param keyword 搜索关键词（LIKE %keyword%）
     * @param cursorId 游标（用户 id，0 表示首次查询）
     * @param limit 返回行数限制
     * @return 匹配的用户列表，按 id 倒序
     */
    suspend fun findByUsernameContainingById(
        em: EntityManager,
        keyword: String,
        cursorId: Long,
        limit: Int
    ): List<UserEntity> {
        val query = em.createQuery(
            "SELECT u FROM UserEntity u " +
                "WHERE u.username LIKE :keyword " +
                "AND (:cursorId = 0 OR u.id < :cursorId) " +
                "ORDER BY u.id DESC",
            UserEntity::class.java
        )
        query.setParameter("keyword", "%$keyword%")
        query.setParameter("cursorId", cursorId)
        query.maxResults = limit
        return query.resultList
    }
}
