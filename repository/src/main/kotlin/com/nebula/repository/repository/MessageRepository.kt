package com.nebula.repository.repository

import com.nebula.repository.entity.MessageEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 消息数据仓库。
 *
 * 提供基于 Snowflake ID 的游标分页查询（D-12）。
 */
interface MessageRepository : JpaRepository<MessageEntity, Long> {

    /** 向后拉取（更旧的消息）— cursor 为上一页最后一条消息的 id */
    @Query("""
        SELECT m FROM MessageEntity m
        WHERE m.conversationId = :convId AND m.id < :cursor
        ORDER BY m.id DESC
    """)
    fun findMessagesBackward(
        @Param("convId") conversationId: String,
        @Param("cursor") cursor: Long,
        pageable: Pageable
    ): List<MessageEntity>

    /** 向前拉取（更新的消息）— cursor 为当前第一条消息的 id */
    @Query("""
        SELECT m FROM MessageEntity m
        WHERE m.conversationId = :convId AND m.id > :cursor
        ORDER BY m.id ASC
    """)
    fun findMessagesForward(
        @Param("convId") conversationId: String,
        @Param("cursor") cursor: Long,
        pageable: Pageable
    ): List<MessageEntity>

    /**
     * 统计会话中的消息总数（D-81）。
     *
     * 用于 SeqService 启动恢复时计算初始序列号。
     *
     * @param conversationId 会话 ID
     * @return 该会话的消息总数
     */
    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId")
    fun countByConversationId(
        @Param("convId") conversationId: String
    ): Long
}
