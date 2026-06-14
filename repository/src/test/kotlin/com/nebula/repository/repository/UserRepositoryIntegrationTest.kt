package com.nebula.repository.repository

import com.nebula.repository.entity.UserEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * 用户实体的 JPA 集成测试。
 *
 * 使用 Hibernate Configuration 直接构建 SessionFactory（不依赖 Spring Data），
 * 通过 JPA EntityManager API 验证 [UserEntity] 的增删改查操作与核心查询逻辑。
 *
 * 测试场景对应 [UserRepository] 的主要方法：
 * - findByUsername → findByUsernameContaining（游标分页查询）
 * - findAllById（批量 ID 查询）
 * - 保存、更新、删除、唯一约束校验
 *
 * 数据库由 TestContainers + Flyway 初始化，Schema 用 validate 模式校验。
 */
class UserRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory

    @BeforeAll
    fun setUp() {
        emf = createEntityManagerFactory()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) {
            emf.close()
        }
    }

    // ==================== EntityManagerFactory 初始化 ====================

    /**
     * 使用 Hibernate Configuration 构建 EntityManagerFactory。
     *
     * - 数据源复用 DatabaseTestBase 的 HikariCP DataSource
     * - Schema 校验模式使用 validate，表结构由 Flyway 保证
     * - 仅注册 UserEntity 实体类
     */
    private fun createEntityManagerFactory(): EntityManagerFactory {
        val config = Configuration()
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "true")
        config.addAnnotatedClass(UserEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        return config.buildSessionFactory()
    }

    // ==================== 测试数据工具 ====================

    /** 计数器，确保每次测试使用不同的用户名，避免唯一约束冲突 */
    private var userCounter = 0L

    /** 生成自增雪崩 ID — 高 32 位固定，低 32 位递增 */
    private fun nextId(): Long = 2000000000L + (++userCounter)

    /** 生成唯一用户名 */
    private fun uniqueUsername(prefix: String = "test_user"): String = "${prefix}_${userCounter}"

    /**
     * 创建一个默认的测试用户实体。
     *
     * @param username 用户名，默认自动生成
     * @return 已填充完整字段的 [UserEntity]
     */
    private fun createTestUser(username: String = uniqueUsername()): UserEntity = UserEntity(
        username = username,
        passwordHash = "\$2a\$10\$testPasswordHashForIntegrationTest",
        nickname = "test_user_$userCounter",
        avatar = "https://example.com/avatar_$userCounter.png",
        privacyStatus = 0
    ).apply {
        id = nextId()
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }

    // ==================== EntityManager 事务工具 ====================

    /**
     * 在 JPA EntityManager 事务内执行写入操作，自动提交。
     *
     * @param block 需要事务包裹的操作，返回实体或任意结果
     * @return block 的返回值
     */
    private fun <T> doInTransaction(block: (EntityManager) -> T): T {
        val em = emf.createEntityManager()
        em.transaction.begin()
        return try {
            val result = block(em)
            em.transaction.commit()
            result
        } catch (e: Exception) {
            em.transaction.rollback()
            throw e
        } finally {
            em.close()
        }
    }

    /**
     * 使用只读 EntityManager 执行查询（不开启写入事务）。
     *
     * @param block 查询操作
     * @return block 的返回值
     */
    private fun <T> doInReadOnly(block: (EntityManager) -> T): T {
        val em = emf.createEntityManager()
        return try {
            block(em)
        } finally {
            em.close()
        }
    }

    // ==================== 基本 CRUD 测试 ====================

    /** 保存新用户后，通过 ID 查找并验证所有字段与原始值一致。 */
    @Test
    fun saveAndFindUserById() {
        val user = createTestUser()

        val savedId = doInTransaction { em ->
            em.persist(user)
            requireNotNull(user.id)
        }

        val found = doInReadOnly { em ->
            em.find(UserEntity::class.java, savedId)
        }

        val savedUser = requireNotNull(found, "Should find recently saved user by ID")
        with(savedUser) {
            assertEquals(user.username, username, "Username should match")
            assertEquals(user.passwordHash, passwordHash, "Password hash should match")
            assertEquals(user.nickname, nickname, "Nickname should match")
            assertEquals(user.avatar, avatar, "Avatar URL should match")
            assertEquals(user.privacyStatus, privacyStatus, "Privacy status should match")
            assertNotNull(id, "ID 不应为空")
            assertNotNull(createdAt, "CreatedAt should not be null")
            assertNotNull(updatedAt, "UpdatedAt should not be null")
        }
    }

    /** 通过 JPQL 查询用户名（模拟 UserRepository.findByUsername）。 */
    @Test
    fun findByUsername() {
        val user = createTestUser()
        doInTransaction { em ->
            em.persist(user)
        }

        val found = doInReadOnly { em ->
            em.createQuery(
                "SELECT u FROM UserEntity u WHERE u.username = :username",
                UserEntity::class.java
            ).setParameter("username", user.username)
                .resultList
                .firstOrNull()
        }

        val foundUser = requireNotNull(found, "Should find user by username")
        assertEquals(user.id, foundUser.id, "Queried user ID should match")
        assertEquals(user.nickname, foundUser.nickname, "Queried nickname should match")
    }

    /** 查询Non-existent username should return null。 */
    @Test
    fun findByNonExistentUsernameReturnsNull() {
        val found = doInReadOnly { em ->
            em.createQuery(
                "SELECT u FROM UserEntity u WHERE u.username = :username",
                UserEntity::class.java
            ).setParameter("username", "non_existent_user_${System.nanoTime()}")
                .resultList
                .firstOrNull()
        }

        assertNull(found, "Non-existent username should return null")
    }

    /** 通过 ID 批量查询（模拟 UserRepository.findAllById）。 */
    @Test
    fun findAllByIds() {
        val user1 = createTestUser("batch_user_1")
        val user2 = createTestUser("batch_user_2")
        val user3 = createTestUser("batch_user_3")

        doInTransaction { em ->
            em.persist(user1)
            em.persist(user2)
            em.persist(user3)
        }

        // 查询 user1 和 user3（跳过 user2）
        val targetIds = listOf(requireNotNull(user1.id), requireNotNull(user3.id))

        val found = doInReadOnly { em ->
            em.createQuery(
                "SELECT u FROM UserEntity u WHERE u.id IN :ids ORDER BY u.id",
                UserEntity::class.java
            ).setParameter("ids", targetIds)
                .resultList
        }

        assertEquals(2, found.size, "Should return 2 matching user records")
        assertEquals(user1.id, found[0].id, "First should match user1")
        assertEquals(user3.id, found[1].id, "Second should match user3")
    }

    /** 批量查询时，部分 ID 不存在应只返回存在的记录。 */
    @Test
    fun findAllByIdsWithPartialMatch() {
        val user = createTestUser("partial_user")
        doInTransaction { em ->
            em.persist(user)
        }

        val targetIds = listOf(requireNotNull(user.id), 9999999999999L)

        val found = doInReadOnly { em ->
            em.createQuery(
                "SELECT u FROM UserEntity u WHERE u.id IN :ids",
                UserEntity::class.java
            ).setParameter("ids", targetIds)
                .resultList
        }

        assertEquals(1, found.size, "Only existing IDs should be returned, missing IDs ignored")
        assertEquals(user.id, found[0].id)
    }

    // ==================== 用户名唯一约束测试 ====================

    /**
     * 插入相同用户名的两个用户应违反唯一约束（uk_username）。
     * 第一个用户成功持久化后，第二个应抛出异常。
     */
    @Test
    fun usernameUniquenessConstraintViolation() {
        val sharedUsername = "conflict_user_${System.nanoTime()}"
        val user1 = createTestUser(sharedUsername)

        // 先保存第一个用户
        doInTransaction { em ->
            em.persist(user1)
        }

        // 尝试插入相同用户名的第二个用户
        val user2 = UserEntity(
            username = sharedUsername,
            passwordHash = "\$2a\$10\$anotherHashForConflictTest",
            nickname = "conflictUser"
        ).apply {
            id = nextId()
            createdAt = LocalDateTime.now()
            updatedAt = LocalDateTime.now()
        }

        assertFailsWith<Exception> {
            doInTransaction { em ->
                em.persist(user2)
            }
        }

        // 验证第一个用户仍然存在，数据未被污染
        val originalStillExists = doInReadOnly { em ->
            em.createQuery(
                "SELECT u FROM UserEntity u WHERE u.username = :username",
                UserEntity::class.java
            ).setParameter("username", sharedUsername)
                .resultList
                .firstOrNull()
        }
        val stillExists = requireNotNull(originalStillExists, "First user should still exist")
        assertEquals(user1.id, stillExists.id)
    }

    // ==================== 更新与删除测试 ====================

    /** 更新用户各字段后，重新查询验证字段值已变更。 */
    @Test
    fun updateUserFields() {
        val user = createTestUser()
        doInTransaction { em ->
            em.persist(user)
        }

        // 更新字段
        doInTransaction { em ->
            val loaded = em.find(UserEntity::class.java, requireNotNull(user.id))
            val userToUpdate = requireNotNull(loaded, "Should find the just-saved user")
            userToUpdate.nickname = "updatedNickname"
            userToUpdate.avatar = "https://example.com/new_avatar.png"
            userToUpdate.privacyStatus = 2
            userToUpdate.passwordHash = "\$2a\$10\$updatedHashForTest"
            userToUpdate.updatedAt = LocalDateTime.now()
            em.merge(userToUpdate)
        }

        // 验证更新结果
        val updated = doInReadOnly { em ->
            em.find(UserEntity::class.java, requireNotNull(user.id))
        }

        val updatedUser = requireNotNull(updated, "Should still find user after update")
        with(updatedUser) {
            assertEquals("updatedNickname", nickname, "Nickname should be updated")
            assertEquals("https://example.com/new_avatar.png", avatar, "Avatar URL should be updated")
            assertEquals(2, privacyStatus, "Privacy status should be updated to hidden")
            assertEquals("\$2a\$10\$updatedHashForTest", passwordHash, "Password hash should be updated")
            // 注意：username 和 id 不应变更
            assertEquals(user.username, username, "Username should not be changed")
            assertEquals(user.id, id, "ID 不应被更新")
        }
    }

    /** 删除用户后，再次查询应返回 null。 */
    @Test
    fun deleteUser() {
        val user = createTestUser()
        doInTransaction { em ->
            em.persist(user)
        }

        // 执行删除
        doInTransaction { em ->
            val loaded = em.find(UserEntity::class.java, requireNotNull(user.id))
            val userToRemove = requireNotNull(loaded, "Should find user before deletion")
            em.remove(userToRemove)
        }

        // 验证删除结果
        val deleted = doInReadOnly { em ->
            em.find(UserEntity::class.java, requireNotNull(user.id))
        }

        assertNull(deleted, "Should return null after deletion")
    }

    /** 删除不存在的用户应不会抛出异常。 */
    @Test
    fun deleteNonExistentUserDoesNothing() {
        val nonExistentId = 9999999999998L

        try {
            doInTransaction { em ->
                val loaded = em.find(UserEntity::class.java, nonExistentId)
                if (loaded != null) {
                    em.remove(loaded)
                }
            }
        } catch (e: Exception) {
            fail("Deleting non-existent user should not throw: ${e.message}")
        }
    }
}
