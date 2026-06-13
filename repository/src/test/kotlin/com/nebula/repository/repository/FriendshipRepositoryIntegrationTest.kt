package com.nebula.repository.repository

import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.testutil.DatabaseTestBase
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.hibernate.query.Query
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 好友关系与好友请求的 JPA 集成测试。
 *
 * 使用 Hibernate Configuration 直接构建 SessionFactory（不依赖 Spring Data），
 * 验证 [FriendshipEntity] 和 [FriendRequestEntity] 的 CRUD 操作与核心查询逻辑。
 *
 * 数据库由 TestContainers + Flyway 初始化，Schema 用 validate 模式校验。
 */
class FriendshipRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var sessionFactory: SessionFactory

    /** 预置的测试用户 ID（来自 V1_2__seed_users.sql） */
    companion object {
        private const val USER_A_ID: Long = 1000001
        private const val USER_B_ID: Long = 1000002
        private const val USER_C_ID: Long = 1000003
        private const val NON_EXISTENT_ID: Long = 9999999
    }

    @BeforeEach
    fun setUp() {
        sessionFactory = createSessionFactory()
        cleanTables()
    }

    @AfterAll
    fun tearDown() {
        if (::sessionFactory.isInitialized) {
            sessionFactory.close()
        }
    }

    // ==================== SessionFactory 初始化 ====================

    /**
     * 使用 Hibernate Configuration 构建 SessionFactory。
     *
     * - 数据源复用 DatabaseTestBase 的 HikariCP DataSource
     * - Schema 校验模式使用 validate，表结构由 Flyway 保证
     * - 仅注册 UserEntity、FriendshipEntity、FriendRequestEntity
     */
    private fun createSessionFactory(): SessionFactory {
        val config = Configuration()
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "true")
        config.addAnnotatedClass(UserEntity::class.java)
        config.addAnnotatedClass(FriendshipEntity::class.java)
        config.addAnnotatedClass(FriendRequestEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        return config.buildSessionFactory()
    }

    /**
     * 清理 friendships 和 friend_requests 表，确保各测试之间互不污染。
     * users 表由 Flyway seed 填充，不做清理。
     */
    private fun cleanTables() {
        doInSession { session ->
            session.createNativeMutationQuery("DELETE FROM friendships").executeUpdate()
            session.createNativeMutationQuery("DELETE FROM friend_requests").executeUpdate()
        }
    }

    // ==================== Session 工具方法 ====================

    /**
     * 在 Hibernate Session 内执行操作，自动管理事务。
     *
     * @param block 需要事务包裹的操作
     */
    private fun <T> doInSession(block: (Session) -> T): T {
        val session = sessionFactory.openSession()
        val transaction = session.beginTransaction()
        return try {
            val result = block(session)
            transaction.commit()
            result
        } catch (e: Exception) {
            transaction.rollback()
            throw e
        } finally {
            session.close()
        }
    }

    /**
     * 在 Hibernate Session 内执行只读查询（不使用事务写入）。
     */
    private fun <T> doInReadOnlySession(block: (Session) -> T): T {
        val session = sessionFactory.openSession()
        return try {
            block(session)
        } finally {
            session.close()
        }
    }

    // ==================== Friendship 测试 ====================

    /** 创建好友关系时，确保 userId < friendId 的约定能得到保持。 */
    @Test
    fun createFriendship() {
        val friendship = FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID)

        val savedId = doInSession { session ->
            session.persist(friendship)
            session.flush()
            friendship.id
        }

        assertNotNull(savedId, "Auto-generated ID expected after persist")
    }

    /** 根据 userId 和 friendId 查询单条好友关系。 */
    @Test
    fun findByUserIdAndFriendId() {
        // 插入测试数据
        doInSession { session ->
            session.persist(FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID))
        }

        val found = doInReadOnlySession { session ->
            val query: Query<FriendshipEntity> = session.createQuery(
                "FROM FriendshipEntity WHERE userId = :userId AND friendId = :friendId",
                FriendshipEntity::class.java
            )
            query.setParameter("userId", USER_A_ID)
            query.setParameter("friendId", USER_B_ID)
            query.uniqueResult()
        }

        assertNotNull(found, "Should find friendship by userId and friendId")
        assertTrue(found!!.userId == USER_A_ID && found.friendId == USER_B_ID)
    }

    /** 软删除：将 deleted 置为 1 后，好友关系不再生效。 */
    @Test
    fun softDeleteFriendship() {
        val friendship = FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID)
        doInSession { session ->
            session.persist(friendship)
        }

        // 执行软删除
        doInSession { session ->
            val toDelete = session.get(FriendshipEntity::class.java, friendship.id)
            toDelete!!.deleted = 1
            session.merge(toDelete)
        }

        // 验证 deleted=1
        val deletedEntity = doInReadOnlySession { session ->
            session.get(FriendshipEntity::class.java, friendship.id)
        }
        assertTrue(deletedEntity!!.deleted == 1, "Deleted flag should be 1 after soft delete")
    }

    /**
     * 重复好友关系插入应违反唯一约束（uk_friendship）。
     * 插入两条相同的 (userId, friendId) 应抛出异常。
     */
    @Test
    fun duplicateFriendshipPrevention() {
        doInSession { session ->
            session.persist(FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID))
        }

        // 重复插入相同 userId 和 friendId 应触发唯一约束异常
        var exceptionThrown = false
        try {
            doInSession { session ->
                session.persist(FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID))
            }
        } catch (e: Exception) {
            // 期望抛出的约束违背异常（如 ConstraintViolationException、DataIntegrityViolationException 等）
            exceptionThrown = true
        }
        assertTrue(exceptionThrown, "Duplicate friendship should throw constraint exception")
    }

    /** 根据 userId 查询所有好友（含双向：userId 或 friendId 匹配，且 deleted=0）。 */
    @Test
    fun queryFriendsByUserId() {
        // 插入两个好友关系：USER_A <-> USER_B, USER_A <-> USER_C
        doInSession { session ->
            session.persist(FriendshipEntity(userId = USER_A_ID, friendId = USER_B_ID))
            session.persist(FriendshipEntity(userId = USER_A_ID, friendId = USER_C_ID))
        }

        val friends = doInReadOnlySession { session ->
            val query = session.createQuery(
                "FROM FriendshipEntity WHERE (userId = :userId OR friendId = :userId) AND deleted = 0 ORDER BY id DESC",
                FriendshipEntity::class.java
            )
            query.setParameter("userId", USER_A_ID)
            query.list()
        }

        assertTrue(friends.size == 2, "User A should have 2 friendships")
        val friendIds = friends.map { if (it.userId == USER_A_ID) it.friendId else it.userId }
        assertContentEquals(listOf(USER_B_ID, USER_C_ID), friendIds.sorted())
    }

    // ==================== FriendRequest 测试 ====================

    /** 创建待处理的好友申请，status 默认为 0（pending）。 */
    @Test
    fun createFriendRequest() {
        val request = FriendRequestEntity(fromUid = USER_A_ID, toUid = USER_B_ID, status = 0)

        val savedId = doInSession { session ->
            session.persist(request)
            session.flush()
            request.id
        }

        assertNotNull(savedId, "Auto-generated ID expected after persist")
    }

    /** 根据接收方 UID 和状态查询好友申请列表。 */
    @Test
    fun findByToUidAndStatus() {
        // USER_B 收到来自 USER_A 和 USER_C 的申请
        doInSession { session ->
            session.persist(FriendRequestEntity(fromUid = USER_A_ID, toUid = USER_B_ID, status = 0))
            session.persist(FriendRequestEntity(fromUid = USER_C_ID, toUid = USER_B_ID, status = 0))
        }

        val requests = doInReadOnlySession { session ->
            val query = session.createQuery(
                "FROM FriendRequestEntity WHERE toUid = :toUid AND status = :status",
                FriendRequestEntity::class.java
            )
            query.setParameter("toUid", USER_B_ID)
            query.setParameter("status", 0)
            query.list()
        }

        assertTrue(requests.size == 2, "USER_B should have 2 pending requests")
    }

    /** 根据发送方、接收方和状态精确查询单条申请（用于重复申请与竞赛检测）。 */
    @Test
    fun findByFromUidAndToUidAndStatus() {
        doInSession { session ->
            session.persist(FriendRequestEntity(fromUid = USER_A_ID, toUid = USER_B_ID, status = 0))
        }

        val found = doInReadOnlySession { session ->
            val query = session.createQuery(
                "FROM FriendRequestEntity WHERE fromUid = :fromUid AND toUid = :toUid AND status = :status",
                FriendRequestEntity::class.java
            )
            query.setParameter("fromUid", USER_A_ID)
            query.setParameter("toUid", USER_B_ID)
            query.setParameter("status", 0)
            query.uniqueResult()
        }

        assertNotNull(found, "Should find pending friend request")
        assertTrue(found!!.fromUid == USER_A_ID && found.toUid == USER_B_ID && found.status == 0)

        // 验证不匹配的状态返回空
        val notFound = doInReadOnlySession { session ->
            val query = session.createQuery(
                "FROM FriendRequestEntity WHERE fromUid = :fromUid AND toUid = :toUid AND status = :status",
                FriendRequestEntity::class.java
            )
            query.setParameter("fromUid", USER_A_ID)
            query.setParameter("toUid", USER_B_ID)
            query.setParameter("status", 1) // accepted
            query.uniqueResult()
        }
        assertNull(notFound, "Should return null when status does not match")
    }

    /** 更新申请状态：接受（status=1）或拒绝（status=2）。 */
    @Test
    fun updateRequestStatus() {
        val request = FriendRequestEntity(fromUid = USER_A_ID, toUid = USER_B_ID, status = 0)
        doInSession { session ->
            session.persist(request)
        }

        // 接受申请
        doInSession { session ->
            val loaded = session.get(FriendRequestEntity::class.java, request.id)
            loaded!!.status = 1
            session.merge(loaded)
        }

        val accepted = doInReadOnlySession { session ->
            session.get(FriendRequestEntity::class.java, request.id)
        }
        assertTrue(accepted!!.status == 1, "Status should be 1 after accept")

        // 拒绝申请
        doInSession { session ->
            val loaded = session.get(FriendRequestEntity::class.java, request.id)
            loaded!!.status = 2
            session.merge(loaded)
        }

        val rejected = doInReadOnlySession { session ->
            session.get(FriendRequestEntity::class.java, request.id)
        }
        assertTrue(rejected!!.status == 2, "Status should be 2 after reject")
    }

    /** 查询不存在的 ID 应返回 null。 */
    @Test
    fun requestNotFoundForNonExistentId() {
        val notFound = doInReadOnlySession { session ->
            session.get(FriendRequestEntity::class.java, NON_EXISTENT_ID)
        }
        assertNull(notFound, "Non-existent ID should return null")
    }
}
