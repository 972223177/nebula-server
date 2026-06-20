package com.nebula.repository.dao

import com.nebula.repository.entity.DeadLetterEntity
import jakarta.persistence.EntityManager

/**
 * 死信数据访问对象（替代原 DeadLetterRepository）。
 *
 * 提供死信记录的查询、重试相关操作。
 * 支持按状态过滤、按创建时间排序的分页查询。
 */
class DeadLetterDao : EntityDao<DeadLetterEntity>(DeadLetterEntity::class.java) {

    /**
     * 查询指定状态下失败次数低于阈值的死信记录（供补偿任务使用）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param status 死信状态
     * @param maxRetries 最大重试次数阈值
     * @param offset 跳过的记录数
     * @param limit 返回行数限制
     * @return 匹配的死信记录列表
     */
    suspend fun findByStatusAndFailCountLessThan(
        em: EntityManager,
        status: String,
        maxRetries: Int,
        offset: Int,
        limit: Int
    ): List<DeadLetterEntity> = io {
        val query = em.createQuery(
            """
            SELECT d FROM DeadLetterEntity d
            WHERE d.status = :status AND d.failCount < :maxRetries
            ORDER BY d.createdAt ASC
            """.trimIndent(),
            DeadLetterEntity::class.java
        )
        query.setParameter("status", status)
        query.setParameter("maxRetries", maxRetries)
        query.firstResult = offset
        query.maxResults = limit
        query.resultList
    }

    /**
     * 按状态升序查询死信记录（按创建时间排序）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param status 死信状态
     * @param offset 跳过的记录数
     * @param limit 返回行数限制
     * @return 匹配的死信记录列表
     */
    suspend fun findByStatusOrderByCreatedAtAsc(
        em: EntityManager,
        status: String,
        offset: Int,
        limit: Int
    ): List<DeadLetterEntity> = io {
        val query = em.createQuery(
            "SELECT d FROM DeadLetterEntity d WHERE d.status = :status ORDER BY d.createdAt ASC",
            DeadLetterEntity::class.java
        )
        query.setParameter("status", status)
        query.firstResult = offset
        query.maxResults = limit
        query.resultList
    }

    /**
     * 按状态统计死信记录数（M15, D-75）。
     *
     * 用于分页查询时获取精确的过滤后总数，替代从 `findAll()` 取 total 的 Bug。
     *
     * @param em 当前事务的 [EntityManager]
     * @param status 死信状态
     * @return 该状态的死信记录总数
     */
    suspend fun countByStatus(
        em: EntityManager,
        status: String
    ): Long = count(
        em,
        "SELECT COUNT(d) FROM DeadLetterEntity d WHERE d.status = :status",
        "status" to status
    )

    /**
     * 查询 failCount >= 阈值的死信记录（M16, D-75）。
     *
     * 用于标记超过最大重试次数的死信为 permanent_failed。
     * 修复原 `findByStatusAndFailCountLessThan(status, 0)` 的死查询 Bug。
     *
     * @param em 当前事务的 [EntityManager]
     * @param status 死信状态
     * @param minFailCount 最小失败次数阈值
     * @param offset 跳过的记录数
     * @param limit 返回行数限制
     * @return 匹配的死信记录列表
     */
    suspend fun findByStatusAndFailCountGreaterThanEqual(
        em: EntityManager,
        status: String,
        minFailCount: Int,
        offset: Int,
        limit: Int
    ): List<DeadLetterEntity> = io {
        val query = em.createQuery(
            """
            SELECT d FROM DeadLetterEntity d
            WHERE d.status = :status AND d.failCount >= :minFailCount
            ORDER BY d.createdAt ASC
            """.trimIndent(),
            DeadLetterEntity::class.java
        )
        query.setParameter("status", status)
        query.setParameter("minFailCount", minFailCount)
        query.firstResult = offset
        query.maxResults = limit
        query.resultList
    }
}
