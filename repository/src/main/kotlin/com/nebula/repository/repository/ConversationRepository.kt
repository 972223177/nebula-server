package com.nebula.repository.repository

import com.nebula.repository.entity.ConversationEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * 会话数据仓库。
 */
interface ConversationRepository : JpaRepository<ConversationEntity, String> {

    /**
     * 按用户 ID 查询其参与的会话列表（游标分页，按 updatedAt 降序）。
     *
     * D-01 游标分页：cursor 为 null 时首次查询，否则只返回 updatedAt 更旧的数据。
     * 子查询 JOIN ConversationMemberEntity 过滤用户参与且未软删除的会话。
     *
     * @param userId 用户 ID
     * @param cursor 游标（上一次列表最小 updatedAt），null 表示首次查询
     * @param pageable 分页参数（由调用方控制 limit）
     * @return 用户参与的会话实体列表（按更新时间降序）
     */
    @Query("""
        SELECT c FROM ConversationEntity c
        WHERE c.id IN (
            SELECT cm.conversationId FROM ConversationMemberEntity cm
            WHERE cm.userId = :userId AND cm.deleted = 0
        )
        AND (:cursor IS NULL OR c.updatedAt < :cursor)
        ORDER BY c.updatedAt DESC
    """)
    fun findConversationsByUserId(
        @Param("userId") userId: Long,
        @Param("cursor") cursor: LocalDateTime?,
        pageable: Pageable
    ): List<ConversationEntity>

    /**
     * 原子更新会话成员计数（D-82, H22）。
     *
     * 单条 UPDATE 语句保证数据库侧原子性，替代非原子的 loadCount → set → save 模式。
     * 由 Handler 层的事务包裹（TransactionTemplate）保证与成员写入在同一事务内。
     *
     * @param conversationId 会话 ID
     * @param delta 成员计数变化量（+1 表示新增成员，-1 表示移除成员）
     * @return 受影响的行数（0 表示会话不存在）
     */
    @Modifying
    @Query("""
        UPDATE ConversationEntity c
        SET c.memberCount = c.memberCount + :delta,
            c.updatedAt = CURRENT_TIMESTAMP
        WHERE c.id = :convId
    """)
    fun incrementMemberCount(
        @Param("convId") conversationId: String,
        @Param("delta") delta: Int
    ): Int
}
