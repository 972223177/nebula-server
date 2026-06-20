package com.nebula.repository.repository

import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.Session
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * MessageEntity 的 JPA 集成测试（P0-02）。
 *
 * 方案 A 重构（2026-06-20）：从 Spring Data JPA 切换到 Hibernate 原生 API。
 * 验证基于 Snowflake ID 的游标分页查询（D-12）和按会话统计消息数。
 *
 * 数据库由 TestContainers + Flyway 初始化。
 */
class MessageRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory

    /** 测试用会话 ID */
    private val convId = "conv_msg_test"

    /** 自增计数器，确保消息 ID 递增 */
    private var msgCounter = 100000L

    /** 生成递增的消息 ID */
    private fun nextMsgId(): Long = ++msgCounter

    @BeforeAll
    fun setup() {
        emf = createEntityManagerFactory()
    }

    @AfterAll
    fun teardown() {
        if (::emf.isInitialized) {
            emf.close()
        }
    }

    @BeforeEach
    fun cleanUp() {
        doInSession { session ->
            session.createNativeMutationQuery("DELETE FROM messages WHERE conversation_id LIKE 'conv_msg_test%'")
                .executeUpdate()
        }
    }

    private fun createEntityManagerFactory(): EntityManagerFactory {
        val config = Configuration()
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "true")
        config.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        config.addAnnotatedClass(MessageEntity::class.java)
        config.addAnnotatedClass(ConversationMemberEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        return config.buildSessionFactory()
    }

    /** 在会话内执行操作 */
    private fun <T> doInSession(block: (Session) -> T): T {
        val session = (emf as org.hibernate.SessionFactory).openSession()
        val tx = session.beginTransaction()
        return try {
            val result = block(session)
            tx.commit()
            result
        } catch (e: Exception) {
            tx.rollback()
            throw e
        } finally {
            session.close()
        }
    }

    /** 创建测试消息实体 */
    private fun createMessage(id: Long, content: String = "msg-$id"): MessageEntity =
        MessageEntity(
            conversationId = convId,
            senderUid = 1001L,
            messageType = 0,
            content = content,
            clientTs = System.currentTimeMillis(),
            serverTs = System.currentTimeMillis()
        ).apply {
            this.id = id
            createdAt = LocalDateTime.now()
        }

    // ==================== 测试用例 ====================

    /**
     * 验证向后拉取消息（更旧）按 id DESC 排序。
     * 等价于原 MessageRepository.findMessagesBackward 行为。
     */
    @Test
    fun findMessagesBackwardShouldReturnDescOrder() {
        val ids = (1..5).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val cursor = ids[4] + 1 // 大于最大 ID，查询所有
        val result = doInReadOnly { em ->
            val query = em.createQuery(
                "SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.id < :cursor ORDER BY m.id DESC",
                MessageEntity::class.java
            )
            query.setParameter("convId", convId)
            query.setParameter("cursor", cursor)
            query.maxResults = 10
            query.resultList
        }

        assertEquals(5, result.size)
        assertEquals(ids[4], result[0].id) // 最新的在前
        assertEquals(ids[0], result[4].id) // 最旧的在后
    }

    /**
     * 验证向前拉取消息（更新）按 id ASC 排序。
     * 等价于原 MessageRepository.findMessagesForward 行为。
     */
    @Test
    fun findMessagesForwardShouldReturnAscOrder() {
        val ids = (1..3).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val cursor = ids[0] - 1 // 小于最小 ID，查询所有
        val result = doInReadOnly { em ->
            val query = em.createQuery(
                "SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.id > :cursor ORDER BY m.id ASC",
                MessageEntity::class.java
            )
            query.setParameter("convId", convId)
            query.setParameter("cursor", cursor)
            query.maxResults = 10
            query.resultList
        }

        assertEquals(3, result.size)
        assertEquals(ids[0], result[0].id)
        assertEquals(ids[2], result[2].id)
    }

    /**
     * 验证按会话统计消息数。
     * 等价于原 MessageRepository.countByConversationId 行为。
     */
    @Test
    fun countByConversationIdShouldReturnCorrectCount() {
        val ids = (1..4).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val count = doInReadOnly { em ->
            em.createQuery(
                "SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId",
                Long::class.java
            ).setParameter("convId", convId).singleResult
        }

        assertEquals(4L, count, "消息计数应返回 4")
    }

    /** 在只读 EntityManager 内执行查询（不开启写入事务） */
    private fun <T> doInReadOnly(block: (EntityManager) -> T): T {
        val em = emf.createEntityManager()
        return try {
            block(em)
        } finally {
            em.close()
        }
    }
}
