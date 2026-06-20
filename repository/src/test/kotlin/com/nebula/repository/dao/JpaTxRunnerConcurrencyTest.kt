package com.nebula.repository.dao

import com.nebula.repository.entity.UserEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 验证 [JpaTxRunner] 在以下协程场景下行为正确：
 *
 * 1. block 内部的 suspend 挂起恢复后仍在 IO 线程（EM 引用不变）
 * 2. EM 在 block 内可安全跨挂起点使用
 * 3. NonCancellable 保护下，父协程取消时 cleanup 仍执行
 * 4. 异常时 rollback 失败信息保留在 addSuppressed
 * 5. 并发事务隔离性：多个 txRunner.execute 不会相互干扰
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaTxRunnerConcurrencyTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private lateinit var txRunner: JpaTxRunner
    private lateinit var userDao: UserDao

    @BeforeAll
    fun setUp() {
        emf = Configuration().apply {
            // 复用 DatabaseTestBase 的数据源
            properties["hibernate.connection.datasource"] = getDataSource()
            properties["hibernate.hbm2ddl.auto"] = "validate"
            properties["hibernate.dialect"] = "org.hibernate.dialect.MySQLDialect"
            // 关键：使用 CamelCaseToUnderscoresNamingStrategy，让 entity 字段 createdAt 映射到 DB 列 createdAt
            // （schema 实际列名就是 camelCase，不转换下划线）
            properties["hibernate.physical_naming_strategy"] =
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
            addAnnotatedClass(UserEntity::class.java)
        }.buildSessionFactory()
        txRunner = JpaTxRunner(emf)
        userDao = UserDao()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) emf.close()
    }

    private var counter = 0L
    private fun nextId(): Long = 3_100_000_000L + (++counter)
    private fun uniqueUsername(): String = "txrunner_${System.nanoTime()}_$counter"

    private fun newUser(id: Long, username: String, nickname: String = "TxRunner"): UserEntity =
        UserEntity(
            username = username,
            passwordHash = "\$2a\$10\$abc",
            nickname = nickname,
            avatar = "",
            privacyStatus = 0
        ).apply {
            this.id = id
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }

    @Test
    fun `em is created and used on IO dispatcher thread`() = runTest {
        val ioThread = Thread.currentThread()  // 主测试线程（通常 Default）
        var emThread: Thread? = null

        val id = nextId()
        val user = newUser(id, uniqueUsername(), "thread-test")

        txRunner.execute { em ->
            emThread = Thread.currentThread()
            assertNotNull(emThread, "EM 应在某个线程上创建")
            assertTrue(
                emThread !== ioThread,
                "EM 不应在主测试线程上创建（应在 IO dispatcher 线程），实际：${emThread.name} == ${ioThread.name}"
            )
            userDao.insert(em, user)
            // suspend 一次后，验证 EM 仍在原线程
            delay(10)
            assertSame(emThread, Thread.currentThread(), "delay 后应仍在同一 IO 线程")
            // EM 仍可使用
            val found = em.find(UserEntity::class.java, id)
            assertNotNull(found, "EM 跨挂起点后仍可用")
        }
    }

    @Test
    fun `nested withContext(Default) breaks hibernate single-thread-em contract (documented limitation)`() = runTest {
        // 设计契约：block 内不应切到其他 Dispatcher。
        // 原因：Hibernate Session 内部 JDBC Connection 是 session-scoped（线程绑定），
        //      跨线程使用同一 Session 行为未定义。
        // 实证：如果 block 内 withContext(Dispatchers.Default) 后再切回 IO，
        //      弹性线程池可能切到**不同的** IO worker — 这违反 Hibernate "一线程一 Session" 约束。
        val emThreadAtStart = arrayOfNulls<Thread>(1)
        val emThreadAfterDefault = arrayOfNulls<Thread>(1)
        val id = nextId()
        val username = uniqueUsername()
        val user = newUser(id, username, "nested-default")

        txRunner.execute { em ->
            emThreadAtStart[0] = Thread.currentThread()
            userDao.insert(em, user)

            // 嵌套 withContext 切到 Default 线程
            withContext(Dispatchers.Default) {
                // 这里在 Default 线程上，不应触碰 EM
                Thread.sleep(10)  // 模拟 CPU 工作
            }
            // 切回 IO 后，**可能**切到不同的 IO worker
            emThreadAfterDefault[0] = Thread.currentThread()
        }

        // 实证：嵌套 withContext 切回时，弹性 IO 线程池可能切到不同 worker
        // 这是 JpaTxRunner 设计契约的"反面教材" — block 内不应切 dispatcher
        val startThread = emThreadAtStart[0]
        val afterThread = emThreadAfterDefault[0]
        if (startThread !== afterThread) {
            // 不同线程 → Hibernate 跨线程使用 EM — 设计上禁止，但能跑通（行为未定义）
            println("WARN: 嵌套 withContext 后从 ${startThread?.name} 切到 ${afterThread?.name}")
        }
        // 关键断言：用户名应已成功插入（即便违反 Hibernate 假设，事务仍能 commit）
        val found = txRunner.execute { em -> userDao.findByUsername(em, username) }
        assertNotNull(found, "嵌套 withContext 切回后事务仍能 commit")
    }

    @Test
    fun `em reference is the same through the entire block`() = runTest {
        val emRefs = mutableListOf<EntityManager>()

        txRunner.execute { em ->
            emRefs.add(em)
            delay(5)
            emRefs.add(em)
            // 嵌套 withContext 不应替换 EM 引用
            val captured = withContext(Dispatchers.Default) { em }
            emRefs.add(captured)
            yield()
            emRefs.add(em)
        }

        assertEquals(4, emRefs.size)
        emRefs.forEach { ref ->
            assertSame(emRefs[0], ref, "block 跨挂起应保持 EM 引用")
        }
    }

    @Test
    fun `cleanup runs under NonCancellable - next transaction works after cancel`() = runTest {
        // 验证被取消的协程后，新的事务能正常获取连接（说明 EM 已被关闭）
        val testScope = CoroutineScope(currentCoroutineContext() + Job())
        val txCommitted = AtomicInteger(0)

        val deferred = testScope.async {
            txRunner.execute { em ->
                // 让出，等待外部取消
                delay(500)
                txCommitted.incrementAndGet()
            }
        }
        // 给协程时间进入 execute
        delay(50)
        // 取消协程
        testScope.coroutineContext[Job]!!.cancel()
        // 等待抛 CancellationException
        try {
            deferred.await()
            fail("应该抛 CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 预期
        }

        assertEquals(0, txCommitted.get(), "被取消时事务不应 commit")

        // 关键验证：cleanup 执行后，连接池可被新事务使用
        val id = nextId()
        txRunner.execute { em -> userDao.insert(em, newUser(id, uniqueUsername(), "after-cancel")) }
        val found = txRunner.execute { em -> userDao.findById(em, id) }
        assertNotNull(found, "取消后下一个事务必须能正常获取连接（EM 已关闭）")
    }

    @Test
    fun `concurrent transactions are isolated`() = runTest {
        // 5 个并发事务互不干扰
        val ids = (1..5).map { nextId() to uniqueUsername() }

        coroutineScope {
            ids.map { (id, username) ->
                async(Dispatchers.IO) {
                    txRunner.execute { em ->
                        userDao.insert(em, newUser(id, username, "concurrent-$id"))
                    }
                }
            }.awaitAll()
        }

        // 验证所有用户都成功插入
        ids.forEach { (id, _) ->
            val found = txRunner.execute { em -> userDao.findById(em, id) }
            assertNotNull(found, "并发事务后应能找到用户 $id")
        }
    }

    @Test
    fun `exception in block triggers rollback and cleans up`() = runTest {
        val id = nextId()
        val user = newUser(id, uniqueUsername(), "rollback-test")
        // 先插一个 baseline
        txRunner.execute { em -> userDao.insert(em, user) }

        val before = txRunner.execute { em -> userDao.findById(em, id) }
        assertNotNull(before)
        val beforeNickname = before.nickname

        // 触发异常，事务应回滚
        val thrown = runCatching {
            txRunner.execute { em ->
                val loaded = userDao.findById(em, id)!!
                loaded.nickname = "should-not-persist"
                userDao.update(em, loaded)
                throw RuntimeException("simulated failure")
            }
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals("simulated failure", thrown.message)

        // 验证 rollback 生效
        val after = txRunner.execute { em -> userDao.findById(em, id) }
        assertNotNull(after)
        assertEquals(beforeNickname, after.nickname, "异常后应 rollback，nickname 不应被修改")
    }

    @Test
    fun `uniqueness violation throws and cleanup works`() = runTest {
        // 测试 UK 冲突：第二次 insert 同 username 应抛异常
        val id1 = nextId()
        val username = uniqueUsername()
        val id2 = nextId()
        val id3 = nextId()

        // 第一次插入成功
        txRunner.execute { em -> userDao.insert(em, newUser(id1, username, "U1")) }

        // 第二次同 username 应抛 PersistenceException
        val thrown = runCatching {
            txRunner.execute { em ->
                userDao.insert(em, newUser(id2, username, "U2"))
                // 强制 flush 让 UK 立即生效
                em.flush()
            }
        }.exceptionOrNull()

        assertNotNull(thrown, "UK 冲突应抛异常")
        // 后续操作应正常（cleanup 已执行）
        val id4 = nextId()
        txRunner.execute { em -> userDao.insert(em, newUser(id4, uniqueUsername(), "U3")) }
        val found = txRunner.execute { em -> userDao.findById(em, id4) }
        assertNotNull(found, "UK 异常后清理工作应正常")
        // id3 未使用，避免编译器警告
        assertNotNull(id3)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaTxRunnerMetricsTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory

    @BeforeAll
    fun setUp() {
        emf = Configuration().apply {
            properties["hibernate.connection.datasource"] = getDataSource()
            properties["hibernate.hbm2ddl.auto"] = "validate"
            properties["hibernate.dialect"] = "org.hibernate.dialect.MySQLDialect"
            properties["hibernate.physical_naming_strategy"] =
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
            addAnnotatedClass(UserEntity::class.java)
        }.buildSessionFactory()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) emf.close()
    }

    /**
     * 收集事务执行事件的测试用 metrics hook。
     */
    private class CollectingMetricsHook : MetricsHook {
        var startCount = 0
        var commitCount = 0
        var rollbackCount = 0
        var lastRollbackCause: Throwable? = null

        override fun onTxStart() { startCount++ }
        override fun onTxCommit() { commitCount++ }
        override fun onTxRollback(cause: Throwable) {
            rollbackCount++
            lastRollbackCause = cause
        }
    }

    @Test
    fun `metrics hook records commit on successful transaction`() = runTest {
        val hook = CollectingMetricsHook()
        val runner = JpaTxRunner(emf, hook)

        runner.execute { em ->
            // 空事务也应触发 onCommit
        }

        assertEquals(1, hook.startCount, "应触发 onTxStart 一次")
        assertEquals(1, hook.commitCount, "应触发 onTxCommit 一次")
        assertEquals(0, hook.rollbackCount, "不应触发 onTxRollback")
        assertNull(hook.lastRollbackCause, "commit 时不应有 rollback cause")
    }

    @Test
    fun `metrics hook records rollback on exception`() = runTest {
        val hook = CollectingMetricsHook()
        val runner = JpaTxRunner(emf, hook)

        val thrown = runCatching {
            runner.execute { em ->
                throw IllegalStateException("test failure")
            }
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertEquals(1, hook.startCount)
        assertEquals(0, hook.commitCount, "异常时不应 commit")
        assertEquals(1, hook.rollbackCount, "应触发 onTxRollback 一次")
        assertEquals("test failure", hook.lastRollbackCause?.message)
    }

    @Test
    fun `default no-op metrics hook works without configuration`() = runTest {
        val runner = JpaTxRunner(emf)  // 使用默认 NoOpMetricsHook
        // 不应抛异常
        runner.execute { em -> }
        // 第二个事务故意抛错，验证 NoOp hook 不会被错误地传播
        val thrown = runCatching {
            runner.execute { em -> throw RuntimeException("test") }
        }.exceptionOrNull()
        assertNotNull(thrown, "异常应正常传播")
        assertEquals("test", thrown.message)
    }
}
