package com.nebula.repository.dao

import com.nebula.repository.entity.UserEntity
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 端到端集成测试：验证 refactor 后的 DAO + JpaTxRunner 路径。
 *
 * 使用 Hibernate 原生 API 构建 EMF（不依赖 Spring），覆盖：
 * 1. JpaTxRunner.execute 事务管理
 * 2. UserDao.insert / findById / findByUsername / update / deleteById
 * 3. 协程 suspend 函数正确性
 * 4. 唯一约束冲突事务回滚
 *
 * 数据库由 TestContainers + Flyway 初始化。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDaoIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private lateinit var txRunner: JpaTxRunner
    private lateinit var userDao: UserDao

    @BeforeAll
    fun setUp() {
        val config = Configuration()
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "false")
        config.setProperty(
            "hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
        )
        config.addAnnotatedClass(UserEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        emf = config.buildSessionFactory()

        txRunner = JpaTxRunner(emf)
        userDao = UserDao()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) {
            emf.close()
        }
    }

    private var counter = 0L
    private fun nextId(): Long = 3_000_000_000L + (++counter)
    private fun uniqueUsername(prefix: String = "dao_user"): String = "${prefix}_${System.nanoTime()}_${counter}"

    private fun newUser(
        id: Long,
        username: String,
        nickname: String = "Test",
        privacyStatus: Int = 0
    ): UserEntity = UserEntity(
        username = username,
        passwordHash = "\$2a\$10\$abc",
        nickname = nickname,
        avatar = "https://example.com/avatar.png",
        privacyStatus = privacyStatus
    ).apply {
        this.id = id
        this.createdAt = LocalDateTime.now()
        this.updatedAt = LocalDateTime.now()
    }

    @Test
    fun `insert then findById returns the persisted entity`() = runTest {
        val id = nextId()
        val username = uniqueUsername()
        val user = newUser(id, username, nickname = "测试用户")

        val result = txRunner.execute { em -> userDao.insert(em, user) }

        // insert 应当返回已持久化的实体
        assertNotNull(result.id, "insert 应回填 ID")
        assertEquals(username, result.username)
        assertEquals("测试用户", result.nickname)
        // result 与 user 是同一引用
        assertEquals(user.id, result.id)

        // 通过 findById 重新读出
        val found = txRunner.execute { em -> userDao.findById(em, user.id!!) }
        assertNotNull(found, "应能通过 ID 找到")
        assertEquals(username, found.username)
        assertEquals("测试用户", found.nickname)
    }

    @Test
    fun `findByUsername returns the matching user`() = runTest {
        val id = nextId()
        val username = uniqueUsername("findname")
        val user = newUser(id, username, nickname = "Nick")

        txRunner.execute { em -> userDao.insert(em, user) }

        val found = txRunner.execute { em -> userDao.findByUsername(em, username) }
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals(username, found.username)

        val notFound = txRunner.execute { em ->
            userDao.findByUsername(em, "nonexistent_${System.nanoTime()}")
        }
        assertNull(notFound, "不存在的用户名应返回 null")
    }

    @Test
    fun `update modifies the entity fields`() = runTest {
        val id = nextId()
        val username = uniqueUsername("upd")
        val user = newUser(id, username, nickname = "OldNick")
        txRunner.execute { em -> userDao.insert(em, user) }

        // 修改字段后再 update
        txRunner.execute { em ->
            val loaded = userDao.findById(em, id)!!
            loaded.nickname = "NewNick"
            loaded.privacyStatus = 1
            userDao.update(em, loaded)
        }

        val reloaded = txRunner.execute { em -> userDao.findById(em, id) }
        assertNotNull(reloaded)
        assertEquals("NewNick", reloaded.nickname)
        assertEquals(1, reloaded.privacyStatus)
    }

    @Test
    fun `delete removes the entity`() = runTest {
        val id = nextId()
        val username = uniqueUsername("del")
        val user = newUser(id, username, nickname = "ToDelete")
        txRunner.execute { em -> userDao.insert(em, user) }

        assertNotNull(txRunner.execute { em -> userDao.findById(em, id) }, "插入后应能查到")

        txRunner.execute { em -> userDao.deleteById(em, id) }

        assertNull(txRunner.execute { em -> userDao.findById(em, id) }, "删除后应查不到")
    }

    @Test
    fun `unique constraint violation triggers transaction rollback`() = runTest {
        val username = uniqueUsername("uk")
        val id1 = nextId()
        val user1 = newUser(id1, username, nickname = "U1")
        txRunner.execute { em -> userDao.insert(em, user1) }

        val id2 = nextId()
        val user2 = newUser(id2, username, nickname = "U2")

        // 期望失败
        assertFailsWith<Exception> {
            txRunner.execute { em -> userDao.insert(em, user2) }
        }

        // 事务回滚后，user1 仍然存在
        val stillThere = txRunner.execute { em -> userDao.findByUsername(em, username) }
        assertNotNull(stillThere, "事务回滚后第一条记录应保留")
        assertEquals("U1", stillThere.nickname, "回滚后未被污染")
        assertEquals(id1, stillThere.id, "ID 应为第一条的")
    }

    @Test
    fun `transaction commits all dao calls in one block`() = runTest {
        val username1 = uniqueUsername("tx1")
        val username2 = uniqueUsername("tx2")
        val id1 = nextId()
        val id2 = nextId()
        val u1 = newUser(id1, username1)
        val u2 = newUser(id2, username2)

        txRunner.execute { em ->
            userDao.insert(em, u1)
            userDao.insert(em, u2)
        }

        val r1 = txRunner.execute { em -> userDao.findById(em, id1) }
        val r2 = txRunner.execute { em -> userDao.findById(em, id2) }
        assertNotNull(r1, "事务提交后第一条应可见")
        assertNotNull(r2, "事务提交后第二条应可见")
    }

    @Test
    fun `transaction rollback on exception reverts all changes`() = runTest {
        val username = uniqueUsername("rollback")
        val id1 = nextId()
        val id2 = nextId()
        val u1 = newUser(id1, username, nickname = "first")
        val u2 = newUser(id2, username, nickname = "second")

        // 第一条插入成功，第二条触发唯一约束 → 整个事务回滚
        assertFailsWith<Exception> {
            txRunner.execute { em ->
                userDao.insert(em, u1)
                userDao.insert(em, u2)  // 重复用户名，触发 UK 冲突
            }
        }

        // 第一条应被回滚，不应存在
        val r1 = txRunner.execute { em -> userDao.findById(em, id1) }
        assertNull(r1, "事务回滚后第一条应不存在")
    }
}
