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
}
