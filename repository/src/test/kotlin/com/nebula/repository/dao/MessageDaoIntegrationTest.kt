package com.nebula.repository.dao

import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.test.runTest
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端集成测试：验证 refactor 后的 MessageDao 路径。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageDaoIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private lateinit var txRunner: JpaTxRunner
    private lateinit var messageDao: MessageDao

    @BeforeAll
    fun setUp() {
        val config = Configuration()
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "false")
        config.setProperty(
            "hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
        )
        config.addAnnotatedClass(MessageEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        emf = config.buildSessionFactory()
        txRunner = JpaTxRunner(emf)
        messageDao = MessageDao()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) {
            emf.close()
        }
    }

    private var counter = 0L
    /** Snowflake 风格 ID：高位时间戳 + 自增 counter（保证单调递增） */
    private fun snowflakeId(): Long {
        val ts = System.currentTimeMillis() - 1700000000000L  // 简化 Snowflake
        return (ts shl 22) or (++counter and 0x3FFFFF)
    }

    private fun newMessage(
        id: Long,
        convId: String,
        senderUid: Long,
        content: String,
        clientMsgId: String? = null
    ): MessageEntity = MessageEntity(
        conversationId = convId,
        senderUid = senderUid,
        messageType = 1,
        content = content,
        payload = null,
        clientMessageId = clientMsgId,
        clientTs = System.currentTimeMillis(),
        serverTs = System.currentTimeMillis()
    ).apply {
        this.id = id
        this.createdAt = LocalDateTime.now()
    }

    @Test
    fun `insert and findMessagesBackward returns paginated history`() = runTest {
        val convId = "msg-conv-${System.nanoTime()}"
        val sender = 5_000_000_000L
        val ids = (1..5).map { snowflakeId() }

        // 插入 5 条消息
        txRunner.execute { em ->
            ids.forEachIndexed { i, id ->
                messageDao.insert(
                    em,
                    newMessage(id, convId, sender, "消息 ${i + 1}", clientMsgId = "msg-${convId}-${i}")
                )
            }
        }

        // 验证 count
        val count = txRunner.execute { em -> messageDao.countByConversationId(em, convId) }
        assertEquals(5, count)

        // 向后拉取 (cursor 是最大 id + 1)
        val maxId = ids.max()
        val list = txRunner.execute { em ->
            messageDao.findMessagesBackward(em, convId, cursor = maxId + 1, limit = 3)
        }
        assertEquals(3, list.size, "limit=3 应返回 3 条")
        // 按 id DESC 排序
        assertEquals(ids.reversed().take(3), list.map { it.id }, "应按 id DESC 排序")

        // 向前拉取 (cursor 是最小 id - 1)
        val minId = ids.min()
        val forward = txRunner.execute { em ->
            messageDao.findMessagesForward(em, convId, cursor = minId - 1, limit = 3)
        }
        assertEquals(3, forward.size, "limit=3 应返回 3 条")
        assertEquals(ids.sorted().take(3), forward.map { it.id }, "应按 id ASC 排序")
    }

    @Test
    fun `clientMsgId unique constraint prevents duplicate`() = runTest {
        val convId = "uniq-conv-${System.nanoTime()}"
        val sender = 5_000_000_000L
        val clientMsgId = "client-msg-${System.nanoTime()}"

        txRunner.execute { em ->
            messageDao.insert(
                em,
                newMessage(snowflakeId(), convId, sender, "first", clientMsgId = clientMsgId)
            )
        }

        // 插入相同 clientMsgId 应失败
        var threw = false
        try {
            txRunner.execute { em ->
                messageDao.insert(
                    em,
                    newMessage(snowflakeId(), convId, sender, "second", clientMsgId = clientMsgId)
                )
            }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw, "相同 clientMsgId 应触发 UK 冲突")
    }

    @Test
    fun `countByConversationId returns 0 for empty conversation`() = runTest {
        val convId = "empty-conv-${System.nanoTime()}"
        val count = txRunner.execute { em -> messageDao.countByConversationId(em, convId) }
        assertEquals(0, count, "无消息的会话应返回 0")
    }

    @Test
    fun `findMessagesBackward with cursor excludes messages above cursor`() = runTest {
        val convId = "cursor-conv-${System.nanoTime()}"
        val sender = 5_000_000_000L
        val ids = (1..5).map { snowflakeId() }

        txRunner.execute { em ->
            ids.forEachIndexed { i, id ->
                messageDao.insert(em, newMessage(id, convId, sender, "M${i + 1}"))
            }
        }

        // 游标 = ids[2]（即只查询 id < ids[2] 的消息）
        val cursor = ids[2]
        val list = txRunner.execute { em ->
            messageDao.findMessagesBackward(em, convId, cursor = cursor, limit = 10)
        }
        // 应返回 id < cursor 的所有消息，按 id DESC
        val expected = ids.filter { it < cursor }.sortedDescending()
        assertEquals(expected, list.map { it.id }, "应只返回 cursor 之前的消息")
    }
}
