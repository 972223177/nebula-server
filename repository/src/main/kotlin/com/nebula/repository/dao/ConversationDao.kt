package com.nebula.repository.dao

import com.nebula.repository.entity.ConversationEntity
import jakarta.persistence.EntityManager
import java.time.LocalDateTime

/**
 * 会话数据访问对象（替代原 ConversationRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 */
class ConversationDao : EntityDao<ConversationEntity>(ConversationEntity::class.java) {

    /**
     * 按用户 ID 查询其参与的会话列表（游标分页，按 updatedAt 降序）。
     *
     * D-01 游标分页：cursor 为 null 时首次查询，否则只返回 updatedAt 更旧的数据。
     * 子查询 JOIN ConversationMemberEntity 过滤用户参与且未软删除的会话。
     *
     * @param em 当前事务的 [EntityManager]
     * @param userId 用户 ID
     * @param cursor 游标（上一次列表最小 updatedAt），null 表示首次查询
     * @param limit 返回行数限制
     * @return 用户参与的会话实体列表（按更新时间降序）
     */
    suspend fun findConversationsByUserId(
        em: EntityManager,
        userId: Long,
        cursor: LocalDateTime?,
        limit: Int
    ): List<ConversationEntity> {
        val query = em.createQuery(
            """
            SELECT c FROM ConversationEntity c
            WHERE c.id IN (
                SELECT cm.conversationId FROM ConversationMemberEntity cm
                WHERE cm.userId = :userId AND cm.deleted = 0
            )
            AND (:cursor IS NULL OR c.updatedAt < :cursor)
            ORDER BY c.updatedAt DESC
            """.trimIndent(),
            ConversationEntity::class.java
        )
        query.setParameter("userId", userId)
        query.setParameter("cursor", cursor)
        query.maxResults = limit
        return query.resultList
    }

    /**
     * 原子更新会话成员计数（D-82, H22）。
     *
     * 单条 UPDATE 语句保证数据库侧原子性，替代非原子的 loadCount → set → save 模式。
     * 由 Handler 层的事务包裹（[JpaTxRunner]）保证与成员写入在同一事务内。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param delta 成员计数变化量（+1 表示新增成员，-1 表示移除成员）
     * @return 受影响的行数（0 表示会话不存在）
     */
    suspend fun incrementMemberCount(
        em: EntityManager,
        conversationId: String,
        delta: Int
    ): Int {
        return em.createQuery(
            """
            UPDATE ConversationEntity c
            SET c.memberCount = c.memberCount + :delta,
                c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :convId
            """.trimIndent()
        )
            .setParameter("delta", delta)
            .setParameter("convId", conversationId)
            .executeUpdate()
    }

    /**
     * 分页查询所有指定状态的会话（用于启动阶段序列号恢复，D-81/H21）。
     *
     * 使用 setFirstResult + setMaxResults 分页避免一次性加载全表到内存。
     * 调用方按 offset 递增迭代直到结果集为空。
     *
     * @param em 当前事务的 [EntityManager]
     * @param status 状态值（0=正常，1=已解散）
     * @param offset 跳过的记录数
     * @param limit 返回行数限制
     * @return 匹配的会话实体列表
     */
    suspend fun findAllByStatus(
        em: EntityManager,
        status: Int,
        offset: Int,
        limit: Int
    ): List<ConversationEntity> {
        val query = em.createQuery(
            "SELECT c FROM ConversationEntity c WHERE c.status = :status",
            ConversationEntity::class.java
        )
        query.setParameter("status", status)
        query.firstResult = offset
        query.maxResults = limit
        return query.resultList
    }
}
