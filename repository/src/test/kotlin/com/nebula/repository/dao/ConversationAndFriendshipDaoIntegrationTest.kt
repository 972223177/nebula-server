package com.nebula.repository.dao

import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.isActive
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端到端集成测试：验证 refactor 后的 ConversationDao / ConversationMemberDao / FriendshipDao 路径。
 *
 * 覆盖：
 * 1. 会话创建与成员关联
 * 2. 成员未读计数 + 已读回执
 * 3. 软删除（退群/解散）
 * 4. 好友关系（双向查询 + UK 约束）
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationAndFriendshipDaoIntegrationTest : DatabaseTestBase() {

    private lateinit var emf: EntityManagerFactory
    private lateinit var txRunner: JpaTxRunner
    private lateinit var conversationDao: ConversationDao
    private lateinit var memberDao: ConversationMemberDao
    private lateinit var friendshipDao: FriendshipDao

    @BeforeAll
    fun setUp() {
        val config = Configuration()
        config.setProperty("hibernate.hbm2ddl.auto", "validate")
        config.setProperty("hibernate.show_sql", "false")
        config.setProperty(
            "hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
        )
        config.addAnnotatedClass(ConversationEntity::class.java)
        config.addAnnotatedClass(ConversationMemberEntity::class.java)
        config.addAnnotatedClass(FriendshipEntity::class.java)
        config.properties["hibernate.connection.datasource"] = getDataSource()
        emf = config.buildSessionFactory()

        txRunner = JpaTxRunner(emf)
        conversationDao = ConversationDao()
        memberDao = ConversationMemberDao()
        friendshipDao = FriendshipDao()
    }

    @AfterAll
    fun tearDown() {
        if (::emf.isInitialized) {
            emf.close()
        }
    }

    private var counter = 0L
    private fun nextId(): Long = 4_000_000_000L + (++counter)
    private fun nextConvId(): String = "conv-${System.nanoTime()}-${counter}"

    private fun newConversation(id: String, type: Int, name: String, memberCount: Int = 0): ConversationEntity =
        ConversationEntity(
            type = type,
            name = name,
            avatar = "",
            groupOwnerUid = if (type == 2) 1001L else null,
            memberCount = memberCount,
            maxMembers = 200,
            status = 0,
            lastMessageId = 0,
            lastMessagePreview = "",
            lastMessageTs = 0
        ).apply {
            this.id = id
            this.createdAt = LocalDateTime.now()
            this.updatedAt = LocalDateTime.now()
        }

    private fun newMember(convId: String, userId: Long, role: String = "member"): ConversationMemberEntity =
        ConversationMemberEntity(
            conversationId = convId,
            userId = userId,
            role = role,
            lastReadMessageId = 0,
            unreadCount = 0,
            deleted = 0
        ).apply {
            this.joinedAt = LocalDateTime.now()
        }

    @Test
    fun `conversation + member insert and query`() = runTest {
        val convId = nextConvId()
        val ownerId = nextId()
        val member1Id = nextId()
        val member2Id = nextId()

        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(convId, type = 2, name = "测试群", memberCount = 3))
            memberDao.insert(em, newMember(convId, ownerId, role = "owner"))
            memberDao.insert(em, newMember(convId, member1Id))
            memberDao.insert(em, newMember(convId, member2Id))
        }

        val members = txRunner.execute { em -> memberDao.findByConversationId(em, convId) }
        assertEquals(3, members.size, "应能查到 3 个成员")
        assertTrue(members.any { it.role == "owner" && it.userId == ownerId }, "群主存在")

        val conv = txRunner.execute { em -> conversationDao.findById(em, convId) }
        assertNotNull(conv)
        assertEquals("测试群", conv.name)
        assertEquals(3, conv.memberCount)
    }

    @Test
    fun `incrementMemberCount updates count atomically`() = runTest {
        val convId = nextConvId()
        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(convId, type = 2, name = "CountTest", memberCount = 0))
        }

        val affected = txRunner.execute { em -> conversationDao.incrementMemberCount(em, convId, +1) }
        assertEquals(1, affected)

        val reloaded = txRunner.execute { em -> conversationDao.findById(em, convId) }
        assertEquals(1, reloaded?.memberCount)

        // 再 +5
        txRunner.execute { em -> conversationDao.incrementMemberCount(em, convId, +5) }
        val reloaded2 = txRunner.execute { em -> conversationDao.findById(em, convId) }
        assertEquals(6, reloaded2?.memberCount)

        // 减
        txRunner.execute { em -> conversationDao.incrementMemberCount(em, convId, -2) }
        val reloaded3 = txRunner.execute { em -> conversationDao.findById(em, convId) }
        assertEquals(4, reloaded3?.memberCount)
    }

    @Test
    fun `incrementUnreadCount excludes sender`() = runTest {
        val convId = nextConvId()
        val senderId = nextId()
        val userA = nextId()
        val userB = nextId()

        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(convId, type = 2, name = "Unread", memberCount = 3))
            memberDao.insert(em, newMember(convId, senderId))
            memberDao.insert(em, newMember(convId, userA))
            memberDao.insert(em, newMember(convId, userB))
        }

        val affected = txRunner.execute { em -> memberDao.incrementUnreadCount(em, convId, senderId) }
        assertEquals(2, affected, "应该只增加 2 个非发送者的未读")

        val sender = txRunner.execute { em -> memberDao.findByConversationIdAndUserId(em, convId, senderId) }
        val a = txRunner.execute { em -> memberDao.findByConversationIdAndUserId(em, convId, userA) }
        val b = txRunner.execute { em -> memberDao.findByConversationIdAndUserId(em, convId, userB) }
        assertEquals(0, sender?.unreadCount, "发送者不应增加")
        assertEquals(1, a?.unreadCount)
        assertEquals(1, b?.unreadCount)
    }

    @Test
    fun `updateReadReceipt resets unread to zero`() = runTest {
        val convId = nextConvId()
        val userId = nextId()
        val msgId = 999L

        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(convId, type = 2, name = "Read", memberCount = 1))
            memberDao.insert(em, newMember(convId, userId))
        }
        // 先产生一些未读
        txRunner.execute { em -> memberDao.incrementUnreadCount(em, convId, /* sender */ 0) }
        // 已读回执
        val affected = txRunner.execute { em -> memberDao.updateReadReceipt(em, convId, userId, msgId) }
        assertEquals(1, affected)

        val reloaded = txRunner.execute { em -> memberDao.findByConversationIdAndUserId(em, convId, userId) }
        assertEquals(0, reloaded?.unreadCount)
        assertEquals(msgId, reloaded?.lastReadMessageId)
    }

    @Test
    fun `softDeleteByConversationIdAndUserId marks member deleted`() = runTest {
        val convId = nextConvId()
        val userId = nextId()
        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(convId, type = 2, name = "SoftDel", memberCount = 1))
            memberDao.insert(em, newMember(convId, userId))
        }

        val activeBefore = txRunner.execute { em -> memberDao.countActiveByConversationId(em, convId) }
        assertEquals(1, activeBefore, "插入后应有 1 个活跃成员")

        val affected = txRunner.execute { em -> memberDao.softDeleteByConversationIdAndUserId(em, convId, userId) }
        assertEquals(1, affected)

        val member = txRunner.execute { em -> memberDao.findByConversationIdAndUserId(em, convId, userId) }
        assertNotNull(member)
        assertEquals(1, member.deleted, "deleted 应为 1")
        assertTrue(!member.isActive, "isActive 应为 false")

        val activeAfter = txRunner.execute { em -> memberDao.countActiveByConversationId(em, convId) }
        assertEquals(0, activeAfter, "软删除后应无活跃成员")
    }

    @Test
    fun `findConversationsByUserId filters by member participation`() = runTest {
        val userId = nextId()
        val otherUserId = nextId()
        val conv1 = nextConvId()
        val conv2 = nextConvId()
        val conv3 = nextConvId()

        txRunner.execute { em ->
            conversationDao.insert(em, newConversation(conv1, type = 2, name = "C1", memberCount = 1))
            memberDao.insert(em, newMember(conv1, userId))

            conversationDao.insert(em, newConversation(conv2, type = 2, name = "C2", memberCount = 1))
            memberDao.insert(em, newMember(conv2, userId))

            conversationDao.insert(em, newConversation(conv3, type = 2, name = "C3", memberCount = 1))
            memberDao.insert(em, newMember(conv3, otherUserId))  // userId 不参与
        }

        val list = txRunner.execute { em ->
            conversationDao.findConversationsByUserId(em, userId, cursor = null, limit = 10)
        }
        assertEquals(2, list.size, "应只返回 userId 参与的 2 个会话")
        assertTrue(list.all { it.id in listOf(conv1, conv2) })
    }

    @Test
    fun `friendship unique constraint prevents duplicate`() = runTest {
        val userA = nextId()
        val userB = nextId()

        // 插入第一条（AUTO_INCREMENT 由 DB 分配 id）
        val f1 = FriendshipEntity(userId = userA, friendId = userB).apply {
            this.createdAt = LocalDateTime.now()
        }
        val inserted1 = txRunner.execute { em -> friendshipDao.insert(em, f1) }
        assertNotNull(inserted1.id, "AUTO_INCREMENT 应回填 id")

        // 插入重复（userA, userB）应失败
        val f2 = FriendshipEntity(userId = userA, friendId = userB).apply {
            this.createdAt = LocalDateTime.now()
        }
        var threw = false
        try {
            txRunner.execute { em -> friendshipDao.insert(em, f2) }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw, "重复 (userA, userB) 应触发 UK 冲突")

        // 验证 (userA, userB) 仍只有一条
        val list = txRunner.execute { em -> friendshipDao.findByUserIdAndFriendId(em, userA, userB) }
        assertNotNull(list)
        assertEquals(inserted1.id, list?.id, "回滚后只剩第一条")
    }

    @Test
    fun `friendship soft delete via field modification`() = runTest {
        val userA = nextId()
        val userB = nextId()
        val f = FriendshipEntity(userId = userA, friendId = userB).apply {
            this.createdAt = LocalDateTime.now()
            this.deleted = 0
        }
        txRunner.execute { em -> friendshipDao.insert(em, f) }

        val before = txRunner.execute { em -> friendshipDao.findByUserIdAndFriendId(em, userA, userB) }
        assertTrue(before?.isActive == true, "插入后应 active")

        // 软删除通过 update 修改 deleted 字段
        txRunner.execute { em ->
            val loaded = friendshipDao.findByUserIdAndFriendId(em, userA, userB)!!
            loaded.deleted = 1
            friendshipDao.update(em, loaded)
        }

        val after = txRunner.execute { em -> friendshipDao.findByUserIdAndFriendId(em, userA, userB) }
        assertTrue(after?.isActive == false, "软删除后应 inactive")
    }
}
