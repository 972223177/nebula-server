package com.nebula.repository.dao

import com.nebula.repository.entity.MessageEntity
import jakarta.persistence.EntityManager

/**
 * 消息数据访问对象（替代原 MessageRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 * 提供基于 Snowflake ID 的游标分页查询（D-12）。
 */
class MessageDao : EntityDao<MessageEntity>(MessageEntity::class.java) {

    /**
     * 向后拉取（更旧的消息）— cursor 为上一页最后一条消息的 id。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param cursor 游标（上一页最后一条消息的 Snowflake ID）
     * @param limit 返回行数限制
     * @return 消息列表（按 id DESC 排序）
     */
    suspend fun findMessagesBackward(
        em: EntityManager,
        conversationId: String,
        cursor: Long,
        limit: Int
    ): List<MessageEntity> = io {
        val query = em.createQuery(
            """
            SELECT m FROM MessageEntity m
            WHERE m.conversationId = :convId AND m.id < :cursor
            ORDER BY m.id DESC
            """.trimIndent(),
            MessageEntity::class.java
        )
        query.setParameter("convId", conversationId)
        query.setParameter("cursor", cursor)
        query.maxResults = limit
        query.resultList
    }

    /**
     * 向前拉取（更新的消息）— cursor 为当前第一条消息的 id。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @param cursor 游标（当前第一条消息的 Snowflake ID）
     * @param limit 返回行数限制
     * @return 消息列表（按 id ASC 排序）
     */
    suspend fun findMessagesForward(
        em: EntityManager,
        conversationId: String,
        cursor: Long,
        limit: Int
    ): List<MessageEntity> = io {
        val query = em.createQuery(
            """
            SELECT m FROM MessageEntity m
            WHERE m.conversationId = :convId AND m.id > :cursor
            ORDER BY m.id ASC
            """.trimIndent(),
            MessageEntity::class.java
        )
        query.setParameter("convId", conversationId)
        query.setParameter("cursor", cursor)
        query.maxResults = limit
        query.resultList
    }

    /**
     * 统计会话中的消息总数（D-81）。
     *
     * 用于 SeqService 启动恢复时计算初始序列号。
     *
     * @param em 当前事务的 [EntityManager]
     * @param conversationId 会话 ID
     * @return 该会话的消息总数
     */
    suspend fun countByConversationId(
        em: EntityManager,
        conversationId: String
    ): Long = count(
        em,
        "SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId",
        "convId" to conversationId
    )
}
