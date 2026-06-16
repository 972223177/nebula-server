package com.nebula.repository.repository

import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.EntityManagerFactory
import org.hibernate.Session
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * DeadLetterRepository 的 JPA 集成测试（P0-03）。
 *
 * 使用 JpaRepositoryFactory 创建 [DeadLetterRepository] 的 Spring Data JPA 代理，
 * 验证命名约定方法（findByStatusOrderByCreatedAtAsc）
 * 和 @Query 方法（findByStatusAndFailCountLessThan）的正确性。
 *
 * 数据库由 TestContainers + Flyway 初始化。
 */
class DeadLetterRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory

    /** 自增计数器 */
    private var counter = 0L

    /** 生成递增的死信 ID */
    private fun nextId(): Long = 1000000000L + (++counter)

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
            session.createNativeMutationQuery("DELETE FROM dead_letters WHERE conversation_id = 'conv-001'").executeUpdate()
        }
    }

    private fun createEntityManagerFactory(): EntityManagerFactory {
        val config = Configuration()
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "true")
        config.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        config.addAnnotatedClass(DeadLetterEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        return config.buildSessionFactory()
    }

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

    /** 创建测试死信实体 */
    private fun createDeadLetter(
        status: String,
        failCount: Int = 0,
        content: String = "test-content"
    ): DeadLetterEntity = DeadLetterEntity(
        conversationId = "conv-001",
        senderUid = 1001L,
        messageType = 0,
        content = content,
        clientTs = System.currentTimeMillis(),
        failReason = "test failure",
        failCount = failCount,
        status = status
    ).apply {
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    /** 使用 JPA Repository 代理执行查询 */
    private fun <T> withRepository(block: (DeadLetterRepository) -> T): T {
        val em = emf.createEntityManager()
        try {
            val repo = JpaRepositoryFactory(em).getRepository(DeadLetterRepository::class.java)
            return block(repo)
        } finally {
            em.close()
        }
    }

    // ==================== 测试用例 ====================

    @Test
    fun findByStatusOrderByCreatedAtAscShouldReturnOrderedResults() {
        val now = LocalDateTime.now()
        val dl1 = createDeadLetter("pending").apply { createdAt = now.minusHours(2); updatedAt = now.minusHours(2) }
        val dl2 = createDeadLetter("pending").apply { createdAt = now.minusHours(1); updatedAt = now.minusHours(1) }
        val dl3 = createDeadLetter("pending").apply { createdAt = now; updatedAt = now }

        doInSession { session ->
            session.persist(dl1)
            session.persist(dl2)
            session.persist(dl3)
        }

        val result = withRepository { repo ->
            repo.findByStatusOrderByCreatedAtAsc("pending", Pageable.ofSize(10))
        }

        assertEquals(3, result.size, "应返回全部 3 条 pending 记录")
        assertEquals(dl1.id, result[0].id, "最早创建的应在第一位")
        assertEquals(dl2.id, result[1].id, "中间创建的应在第二位")
        assertEquals(dl3.id, result[2].id, "最新创建的应在最后")
    }

    @Test
    fun findByStatusAndFailCountLessThanShouldFilterByFailCount() {
        doInSession { session ->
            session.persist(createDeadLetter("retrying", failCount = 1))
            session.persist(createDeadLetter("retrying", failCount = 3))
            session.persist(createDeadLetter("retrying", failCount = 5))
        }

        val result = withRepository { repo ->
            repo.findByStatusAndFailCountLessThan("retrying", 5, Pageable.ofSize(10))
        }

        assertEquals(2, result.size, "failCount < 5 应返回 2 条")
    }

    @Test
    fun countByStatusShouldReturnCorrectCount() {
        doInSession { session ->
            session.persist(createDeadLetter("pending"))
            session.persist(createDeadLetter("pending"))
            session.persist(createDeadLetter("retrying"))
        }

        val pendingCount = withRepository { repo ->
            repo.countByStatus("pending")
        }
        val retryingCount = withRepository { repo ->
            repo.countByStatus("retrying")
        }

        assertEquals(2L, pendingCount, "pending 状态应有 2 条")
        assertEquals(1L, retryingCount, "retrying 状态应有 1 条")
    }
}
