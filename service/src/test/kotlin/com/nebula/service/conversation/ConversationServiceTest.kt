package com.nebula.service.conversation

import com.nebula.chat.conversation.ConvListResp
import com.nebula.repository.dao.*
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.UserEntity
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.persistence.EntityManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ConversationService 单元测试（mock 风格）。
 *
 * 覆盖 6cd66f7 提交的修复：listConversations 过滤成员全是自己的 private:uid:uid
 * 自会话，避免会话列表出现自己账户。
 */
class ConversationServiceTest {

    private lateinit var conversationDao: ConversationDao
    private lateinit var conversationMemberDao: ConversationMemberDao
    private lateinit var userDao: UserDao
    private lateinit var friendshipDao: FriendshipDao
    private lateinit var txRunner: JpaTxRunner
    private lateinit var em: EntityManager
    private lateinit var conversationService: ConversationService

    private val userId = 5L

    @BeforeEach
    fun setup() {
        conversationDao = mockk()
        conversationMemberDao = mockk()
        userDao = mockk()
        friendshipDao = mockk()
        txRunner = mockk()
        em = mockk(relaxed = true)
        conversationService = ConversationService(
            conversationDao, conversationMemberDao, userDao, friendshipDao, txRunner
        )
        coEvery { txRunner.execute<Any>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }
    }

    @Test
    fun listConversationsShouldFilterSelfOnlyConversation() = runTest {
        val normalConv = ConversationEntity(type = 1, name = "").apply { id = "private:1:2" }
        val selfConv = ConversationEntity(type = 1, name = "").apply { id = "private:5:5" }
        coEvery { conversationDao.findConversationsByUserId(em, userId, any(), any()) } returns
            listOf(normalConv, selfConv)

        // 用户在两个会话的成员映射（仅用于 lastReadMessageId，可为空）；DAO 返回 List，service 内再 associateBy
        coEvery { conversationMemberDao.findByConversationIdsAndUserId(em, any(), any()) } returns emptyList<ConversationMemberEntity>()
        // 两个会话的全部成员：normalConv 含 1、2；selfConv 仅含自己
        coEvery { conversationMemberDao.findAllByConversationIds(em, any()) } returns listOf(
            ConversationMemberEntity("private:1:2", 1),
            ConversationMemberEntity("private:1:2", 2),
            ConversationMemberEntity("private:5:5", 5)
        )
        coEvery { userDao.findAllById(em, listOf(2L)) } returns listOf(
            UserEntity(username = "u2", passwordHash = "h", nickname = "用户2").apply { id = 2L }
        )

        val resp: ConvListResp = conversationService.listConversations(userId, 0L, 50)

        // 自会话(private:5:5) 应在列表中被过滤掉
        assertFalse(
            resp.conversationsList.any { it.conversationId == "private:5:5" },
            "会话列表不应包含成员全是自己的自会话"
        )
        // 正常私聊会话应保留
        assertTrue(
            resp.conversationsList.any { it.conversationId == "private:1:2" },
            "正常私聊会话应保留在列表中"
        )
    }
}
