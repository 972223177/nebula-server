package com.nebula.repository.repository

import com.nebula.repository.entity.DeadLetterEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 死信数据仓库（Phase 10）。
 *
 * 提供死信记录的查询、重试相关操作。
 * 支持按状态过滤、按创建时间排序的分页查询。
 */
interface DeadLetterRepository : JpaRepository<DeadLetterEntity, Long> {

    /**
     * 查询指定状态下失败次数低于阈值的死信记录（供补偿任务使用）。
     *
     * @param status 死信状态
     * @param maxRetries 最大重试次数阈值
     * @param pageable 分页参数
     * @return 匹配的死信记录列表
     */
    @Query("""
        SELECT d FROM DeadLetterEntity d
        WHERE d.status = :status AND d.failCount < :maxRetries
        ORDER BY d.createdAt ASC
    """)
    fun findByStatusAndFailCountLessThan(
        @Param("status") status: String,
        @Param("maxRetries") maxRetries: Int,
        pageable: Pageable
    ): List<DeadLetterEntity>

    /**
     * 按状态升序查询死信记录（按创建时间排序）。
     *
     * @param status 死信状态
     * @param pageable 分页参数
     * @return 匹配的死信记录列表
     */
    fun findByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: Pageable
    ): List<DeadLetterEntity>

    /**
     * 按状态统计死信记录数（M15, D-75）。
     *
     * 用于分页查询时获取精确的过滤后总数，替代从 `findAll()` 取 total 的 Bug。
     *
     * @param status 死信状态
     * @return 该状态的死信记录总数
     */
    @Query("SELECT COUNT(d) FROM DeadLetterEntity d WHERE d.status = :status")
    fun countByStatus(
        @Param("status") status: String
    ): Long

    /**
     * 查询 failCount >= 阈值的死信记录（M16, D-75）。
     *
     * 用于标记超过最大重试次数的死信为 permanent_failed。
     * 修复原 `findByStatusAndFailCountLessThan(status, 0)` 的死查询 Bug。
     *
     * @param status 死信状态
     * @param minFailCount 最小失败次数阈值
     * @param pageable 分页参数
     * @return 匹配的死信记录列表
     */
    @Query("""
        SELECT d FROM DeadLetterEntity d
        WHERE d.status = :status AND d.failCount >= :minFailCount
        ORDER BY d.createdAt ASC
    """)
    fun findByStatusAndFailCountGreaterThanEqual(
        @Param("status") status: String,
        @Param("minFailCount") minFailCount: Int,
        pageable: Pageable
    ): List<DeadLetterEntity>
}
