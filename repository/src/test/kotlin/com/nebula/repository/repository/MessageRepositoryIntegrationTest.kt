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
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * MessageRepository 的 JPA 集成测试（P0-02）。
 *
 * 使用 JpaRepositoryFactory 创建 [MessageRepository] 的 Spring Data JPA 代理，
 * 验证 @Query 方法（findMessagesBackward / findMessagesForward）
 * 和命名约定方法（countByConversationId）的正确性。
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

    /** 使用 JPA Repository 代理执行查询 */
    private fun <T> withRepository(block: (MessageRepository) -> T): T {
        val em = emf.createEntityManager()
        try {
            val repo = JpaRepositoryFactory(em).getRepository(MessageRepository::class.java)
            return block(repo)
        } finally {
            em.close()
        }
    }

    // ==================== 测试用例 ====================

    @Test
    fun findMessagesBackwardShouldReturnDescOrder() {
        val ids = (1..5).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val cursor = ids[4] + 1 // 大于最大 ID，查询所有
        val result = withRepository { repo ->
            repo.findMessagesBackward(convId, cursor, Pageable.ofSize(10))
        }

        // 验证返回结果按 ID DESC 排序
        assertEquals(5, result.size)
        assertEquals(ids[4], result[0].id) // 最新的在前
        assertEquals(ids[0], result[4].id) // 最旧的在后
    }

    @Test
    fun findMessagesForwardShouldReturnAscOrder() {
        val ids = (1..3).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val cursor = ids[0] - 1 // 小于最小 ID，查询所有
        val result = withRepository { repo ->
            repo.findMessagesForward(convId, cursor, Pageable.ofSize(10))
        }

        // 验证返回结果按 ID ASC 排序
        assertEquals(3, result.size)
        assertEquals(ids[0], result[0].id) // 最旧的在前
        assertEquals(ids[2], result[2].id) // 最新的在后
    }

    @Test
    fun countByConversationIdShouldReturnCorrectCount() {
        val ids = (1..4).map { nextMsgId() }
        doInSession { session ->
            ids.forEach { session.persist(createMessage(it)) }
        }

        val count = withRepository { repo ->
            repo.countByConversationId(convId)
        }

        assertEquals(4L, count, "消息计数应返回 4")
    }
}
