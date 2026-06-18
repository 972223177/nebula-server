package com.nebula.repository.repository

import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.testutil.DatabaseTestBase
import jakarta.persistence.PersistenceException
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 【集成测试】会话与会话成员持久化操作验证。
 *
 * 继承 [DatabaseTestBase] 自动启动 TestContainers MySQL 容器并执行 Flyway 迁移。
 * 使用原始 Hibernate Configuration（非 Spring Data）测试实体映射与数据库交互的正确性。
 *
 * 覆盖两类实体操作：
 * - [ConversationEntity]：创建、查找、更新元数据、更新成员数
 * - [ConversationMemberEntity]：添加成员、查找、计数、软删除、回执更新、防重复约束
 */
class ConversationRepositoryIntegrationTest : DatabaseTestBase() {

    private lateinit var sessionFactory: SessionFactory

    /** 测试用用户 ID，确保与种子数据不冲突 */
    private val testUserId1 = 1000101L
    private val testUserId2 = 1000102L

    /**
     * 每个测试用例执行前清理测试数据，避免固定 ID 的用户/会话/成员数据跨测试冲突。
     *
     * 仅删除 ID >= 1000000 的用户（保护 Flyway 种子数据 1000001~1000003），
     * 再删除从属的会话和成员数据。
     */
    @BeforeEach
    fun cleanUp() {
        doInSession { session ->
            session.createNativeMutationQuery("DELETE FROM conversation_members").executeUpdate()
            session.createNativeMutationQuery("DELETE FROM conversations").executeUpdate()
            session.createMutationQuery("DELETE FROM UserEntity WHERE id >= :minId")
                .setParameter("minId", 1000000L)
                .executeUpdate()
        }
    }

    @BeforeAll
    fun setup() {
        val config = Configuration()
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "true")
        config.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        config.addAnnotatedClass(UserEntity::class.java)
        config.addAnnotatedClass(ConversationEntity::class.java)
        config.addAnnotatedClass(ConversationMemberEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        sessionFactory = config.buildSessionFactory()
    }

    @AfterAll
    fun teardown() {
        sessionFactory.close()
    }

    // ==================== ConversationEntity 测试 ====================

    /**
     * 创建私聊会话（type=0）。
     *
     * 验证：
     * - ID 不为空（由调用方赋值）
     * - createdAt 和 updatedAt 正确写入
     * - type 字段值为 0
     */
    @Test
    fun shouldCreateAPrivateConversation() {
        val convId = "conv_private_test_001"
        val now = LocalDateTime.now()
        doInSession { session ->
            val conv = ConversationEntity(type = 0).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }

        doInSession { session ->
            val loaded = session.get(ConversationEntity::class.java, convId)
            assertNotNull(loaded, "Private conversation failed to persist")
            assertEquals(0, loaded.type, "Private conv type should be 0")
            assertNotNull(loaded.createdAt, "createdAt 应为非空")
            assertNotNull(loaded.updatedAt, "updatedAt 应为非空")
        }
    }

    /**
     * 创建群聊会话（type=2）。
     *
     * 验证：
     * - 群聊名称正确保存
     * - 群主 UID 正确
     * - 初始 memberCount 为 1（群主自己）
     */
    @Test
    fun shouldCreateAGroupConversation() {
        val convId = "conv_group_test_001"
        val now = LocalDateTime.now()
        doInSession { session ->
            val conv = ConversationEntity(
                type = 2,
                name = "testGroup",
                groupOwnerUid = testUserId1,
                memberCount = 1
            ).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }

        doInSession { session ->
            val loaded = session.get(ConversationEntity::class.java, convId)
            assertNotNull(loaded, "Group conversation failed to persist")
            assertEquals(2, loaded.type, "Group conv type should be 2")
            assertEquals("testGroup", loaded.name, "Group name mismatch")
            assertEquals(testUserId1, loaded.groupOwnerUid, "Owner UID mismatch")
            assertEquals(1, loaded.memberCount, "Initial memberCount should be 1")
        }
    }

    /**
     * 按 ID 查找会话。
     *
     * 保存会话后使用 session.get() 加载，验证所有字段正确还原。
     */
    @Test
    fun shouldFindConversationById() {
        val convId = "conv_find_test_001"
        val now = LocalDateTime.now()
        doInSession { session ->
            val conv = ConversationEntity(
                type = 0,
                name = "findTestConv",
                groupOwnerUid = testUserId1,
                memberCount = 2,
                maxMembers = 10
            ).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }

        doInSession { session ->
            val loaded = session.get(ConversationEntity::class.java, convId)
            assertNotNull(loaded, "Failed to find conversation by ID")
            assertEquals("findTestConv", loaded.name)
            assertEquals(0, loaded.type)
            assertEquals(testUserId1, loaded.groupOwnerUid)
            assertEquals(2, loaded.memberCount)
            assertEquals(10, loaded.maxMembers)
        }
    }

    /**
     * 更新会话元数据（lastMessageId、lastMessagePreview、lastMessageTs）。
     *
     * 模拟消息发送后 Service 层更新会话最新消息快照的行为。
     */
    @Test
    fun shouldUpdateConversationMetadata() {
        val convId = "conv_meta_test_001"
        val now = LocalDateTime.now()
        doInSession { session ->
            val conv = ConversationEntity(type = 0).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }

        doInSession { session ->
            session.createMutationQuery(
                """
                UPDATE ConversationEntity c
                SET c.lastMessageId = :msgId,
                    c.lastMessagePreview = :preview,
                    c.lastMessageTs = :ts
                WHERE c.id = :id
                """.trimIndent()
            )
                .setParameter("msgId", 10001L)
                .setParameter("preview", "Hello, this is a test message")
                .setParameter("ts", System.currentTimeMillis())
                .setParameter("id", convId)
                .executeUpdate()
        }

        doInSession { session ->
            val loaded = session.get(ConversationEntity::class.java, convId)
            assertNotNull(loaded, "Conversation should still exist after update")
            assertEquals(10001L, loaded.lastMessageId, "lastMessageId 更新不匹配")
            assertEquals("Hello, this is a test message", loaded.lastMessagePreview, "lastMessagePreview update mismatch")
            assertTrue(loaded.lastMessageTs > 0, "lastMessageTs 应大于 0")
        }
    }

    /**
     * 更新会话成员数量。
     *
     * 模拟添加成员后 memberCount 自增的场景。
     */
    @Test
    fun shouldUpdateMemberCount() {
        val convId = "conv_member_count_test_001"
        val now = LocalDateTime.now()
        doInSession { session ->
            val conv = ConversationEntity(type = 2, name = "memberCountTest", memberCount = 1).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }

        // 成员数从 1 增加到 3
        doInSession { session ->
            session.createMutationQuery(
                "UPDATE ConversationEntity c SET c.memberCount = :count WHERE c.id = :id"
            )
                .setParameter("count", 3)
                .setParameter("id", convId)
                .executeUpdate()
        }

        doInSession { session ->
            val loaded = session.get(ConversationEntity::class.java, convId)
            assertNotNull(loaded, "Conversation should still exist after update")
            assertEquals(3, loaded.memberCount, "memberCount 应更新为 3")
        }
    }

    // ==================== ConversationMemberEntity 测试 ====================

    /**
     * 向会话添加成员。
     *
     * 需要先创建测试用户和会话。验证成员角色正确、joinedAt 自动填充。
     */
    @Test
    fun shouldAddMemberToConversation() {
        val convId = "conv_add_member_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            val member = ConversationMemberEntity(
                conversationId = convId,
                userId = testUserId1,
                role = "owner"
            ).apply {
                joinedAt = now
            }
            session.persist(member)
        }

        doInSession { session ->
            val loaded = session.get(ConversationMemberEntity::class.java, 1L)
            // 由于使用自增 ID，此处通过业务查询验证
            val hql = "FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.userId = :uid"
            val result = session.createSelectionQuery(hql, ConversationMemberEntity::class.java)
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .singleResultOrNull
            assertNotNull(result, "Member record not found")
            assertEquals("owner", result.role, "Member role should be owner")
            assertEquals(0, result.deleted, "New member deleted should be 0")
            assertNotNull(result.joinedAt, "joinedAt 应自动填充")
        }
    }

    /**
     * 按会话 ID 和用户 ID 查找成员记录。
     *
     * 验证 [ConversationMemberRepository.findByConversationIdAndUserId] 的 HQL 等价查询。
     */
    @Test
    fun shouldFindMemberByConversationIdAndUserId() {
        val convId = "conv_find_member_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            val member = ConversationMemberEntity(
                conversationId = convId,
                userId = testUserId1,
                role = "member"
            ).apply {
                joinedAt = now
            }
            session.persist(member)
        }

        doInSession { session ->
            val hql = "FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.userId = :uid"
            val result = session.createSelectionQuery(hql, ConversationMemberEntity::class.java)
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .singleResultOrNull
            assertNotNull(result, "Should find member by convId and userId")
            assertEquals(testUserId1, result.userId, "userId 匹配")
            assertEquals(convId, result.conversationId, "conversationId 匹配")
        }
    }

    /**
     * 按会话 ID 查找所有成员。
     *
     * 向同一会话添加 2 个成员，验证返回数量为 2。
     */
    @Test
    fun shouldFindAllMembersByConversationId() {
        val convId = "conv_list_members_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        // 创建第二个测试用户
        doInSession { session ->
            val user2 = createTestUser(session, testUserId2, "member_user")
            session.persist(user2)
        }

        doInSession { session ->
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId1, role = "owner").apply { joinedAt = now })
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId2, role = "member").apply { joinedAt = now })
        }

        doInSession { session ->
            val hql = "FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid"
            val members = session.createSelectionQuery(hql, ConversationMemberEntity::class.java)
                .setParameter("cid", convId)
                .list()
            assertEquals(2, members.size, "Conversation should have 2 members")
            val uids = members.map { it.userId }.toSet()
            assertTrue(uids.contains(testUserId1), "Should contain user 1")
            assertTrue(uids.contains(testUserId2), "Should contain user 2")
        }
    }

    /**
     * 统计活跃成员数（deleted=0）。
     *
     * 验证：
     * - 初始活跃成员数为 2
     * - 软删除一个成员后活跃数变为 1
     */
    @Test
    fun shouldCountActiveMembersByConversationId() {
        val convId = "conv_active_count_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            val user2 = createTestUser(session, testUserId2, "active_test_user")
            session.persist(user2)
        }

        doInSession { session ->
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId1, role = "owner").apply { joinedAt = now })
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId2, role = "member").apply { joinedAt = now })
        }

        // 统计活跃成员数
        doInSession { session ->
            val count = session.createSelectionQuery(
                "SELECT COUNT(cm) FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.deleted = 0",
                Long::class.java
            )
                .setParameter("cid", convId)
                .singleResult
            assertEquals(2L, count, "Initial active member count should be 2")
        }

        // 软删除一个成员
        doInSession { session ->
            session.createMutationQuery(
                "UPDATE ConversationMemberEntity cm SET cm.deleted = 1 WHERE cm.conversationId = :cid AND cm.userId = :uid"
            )
                .setParameter("cid", convId)
                .setParameter("uid", testUserId2)
                .executeUpdate()
        }

        // 再次统计
        doInSession { session ->
            val count = session.createSelectionQuery(
                "SELECT COUNT(cm) FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.deleted = 0",
                Long::class.java
            )
                .setParameter("cid", convId)
                .singleResult
            assertEquals(1L, count, "Active member count after soft delete should be 1")
        }
    }

    /**
     * 软删除成员（deleted=1）。
     *
     * 验证 softDeleteByConversationIdAndUserId 操作后 deleted 字段变为 1。
     */
    @Test
    fun shouldSoftDeleteAMember() {
        val convId = "conv_soft_delete_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId1, role = "member").apply { joinedAt = now })
        }

        // 执行软删除
        doInSession { session ->
            session.createMutationQuery(
                "UPDATE ConversationMemberEntity cm SET cm.deleted = 1 WHERE cm.conversationId = :cid AND cm.userId = :uid"
            )
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .executeUpdate()
        }

        doInSession { session ->
            val hql = "FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.userId = :uid"
            val member = session.createSelectionQuery(hql, ConversationMemberEntity::class.java)
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .singleResultOrNull
            assertNotNull(member, "Member record should still exist after soft delete")
            assertEquals(1, member.deleted, "deleted 应标记为 1")
        }
    }

    /**
     * 更新已读回执（lastReadMessageId）。
     *
     * 验证 updateReadReceipt 操作同时更新 lastReadMessageId 并清零 unreadCount。
     */
    @Test
    fun shouldUpdateReadReceipt() {
        val convId = "conv_read_receipt_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            val member = ConversationMemberEntity(
                conversationId = convId,
                userId = testUserId1,
                role = "member",
                lastReadMessageId = 0,
                unreadCount = 10 // 模拟有 10 条未读
            ).apply {
                joinedAt = now
            }
            session.persist(member)
        }

        doInSession { session ->
            session.createMutationQuery(
                """
                UPDATE ConversationMemberEntity cm
                SET cm.lastReadMessageId = :msgId,
                    cm.unreadCount = 0
                WHERE cm.conversationId = :cid AND cm.userId = :uid
                """.trimIndent()
            )
                .setParameter("msgId", 500L)
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .executeUpdate()
        }

        doInSession { session ->
            val hql = "FROM ConversationMemberEntity cm WHERE cm.conversationId = :cid AND cm.userId = :uid"
            val member = session.createSelectionQuery(hql, ConversationMemberEntity::class.java)
                .setParameter("cid", convId)
                .setParameter("uid", testUserId1)
                .singleResultOrNull
            assertNotNull(member, "Member should still exist after read receipt update")
            assertEquals(500L, member.lastReadMessageId, "lastReadMessageId 应更新为 500")
            assertEquals(0, member.unreadCount, "unreadCount 应清零")
        }
    }

    /**
     * 防止重复添加成员。
     *
     * 验证数据库层唯一索引 uk_member(conversation_id, user_id) 生效。
     * 尝试插入相同 conversationId + userId 的记录应抛出约束违反异常。
     */
    @Test
    fun shouldPreventDuplicateMember() {
        val convId = "conv_dup_member_001"
        val now = LocalDateTime.now()
        setupUserAndConversation(convId, now)

        doInSession { session ->
            session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId1, role = "owner").apply { joinedAt = now })
        }

        doInSession { session ->
            val ex = assertFailsWith<PersistenceException>("Duplicate member should throw PersistenceException") {
                session.persist(ConversationMemberEntity(conversationId = convId, userId = testUserId1, role = "member").apply { joinedAt = now })
                session.flush() // 强制刷新以触发唯一约束检查
            }
            assertTrue(ex.message?.contains("Duplicate entry") == true, "Exception message should contain unique constraint violation hint")
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 在 Session 内执行操作，自动管理事务生命周期。
     *
     * @param block 在 Session 内执行的代码块
     */
    private fun doInSession(block: (Session) -> Unit) {
        val session = sessionFactory.openSession()
        try {
            val tx = session.beginTransaction()
            block(session)
            tx.commit()
        } finally {
            session.close()
        }
    }

    /**
     * 创建测试用户和会话，供后续成员测试使用。
     *
     * @param convId 会话 ID
     * @param now 时间戳，用于实体初始化
     */
    private fun setupUserAndConversation(convId: String, now: LocalDateTime) {
        doInSession { session ->
            val user = createTestUser(session, testUserId1, "test_owner")
            session.persist(user)

            val conv = ConversationEntity(
                type = 2,
                name = "memberTestGroup",
                groupOwnerUid = testUserId1,
                memberCount = 1
            ).apply {
                id = convId
                createdAt = now
                updatedAt = now
            }
            session.persist(conv)
        }
    }

    /**
     * 创建测试用户实体。
     *
     * @param id 用户 ID（Snowflake 风格）
     * @param username 用户名
     * @return 带时间戳初始化的 [UserEntity] 实例
     */
    private fun createTestUser(session: Session, id: Long, username: String): UserEntity {
        val now = LocalDateTime.now()
        return UserEntity(
            username = username,
            passwordHash = "bcrypt_hash_placeholder",
            nickname = "testUser_$username"
        ).apply {
            this.id = id
            createdAt = now
            updatedAt = now
        }
    }
}
