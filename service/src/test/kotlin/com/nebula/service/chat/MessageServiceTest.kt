package com.nebula.service.chat

import com.nebula.chat.ChatContentType
import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ChatException
import com.nebula.common.exception.MessageException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.service.sequence.SeqService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.util.*
import kotlin.test.*

/**
 * MessageService 单元测试（MockK，strict 模式）。
 *
 * 覆盖 sendMessage / pullMessages / readReport 三个方法的正常流程和异常分支。
 */
class MessageServiceTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var messageQueueRepository: MessageQueueRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var friendshipRepository: FriendshipRepository
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var seqService: SeqService
    private lateinit var messageService: MessageService

    /** 测试用常量 */
    private val conversationId = "conv-001"
    private val senderUid = 1L
    private val userId = 100L
    private val mockMsgId = 2024000000001L
    private val now = System.currentTimeMillis()

    /** 构造群聊会话实体 */
    private fun groupConversation(): ConversationEntity =
        ConversationEntity(type = 2, name = "测试群").apply { id = conversationId }

    /** 构造私聊会话实体 */
    private fun privateConversation(): ConversationEntity =
        ConversationEntity(type = 0, name = "").apply { id = "private:1:2" }

    /** 构造活跃成员实体 */
    private fun activeMember(convId: String = conversationId, uid: Long = senderUid): ConversationMemberEntity =
        ConversationMemberEntity(conversationId = convId, userId = uid, deleted = 0)

    /** 构造已删除成员实体 */
    private fun deletedMember(convId: String = conversationId, uid: Long = senderUid): ConversationMemberEntity =
        ConversationMemberEntity(conversationId = convId, userId = uid, deleted = 1)

    /** 构造消息实体 */
    private fun messageEntity(id: Long, content: String = "msg-$id"): MessageEntity =
        MessageEntity(
            conversationId = conversationId,
            senderUid = senderUid,
            messageType = ChatContentType.TEXT_VALUE,
            content = content,
            clientTs = now,
            serverTs = now
        ).apply { this.id = id }

    @BeforeEach
    fun setUp() {
        // strict 模式：所有 mock 未显式设置的行为会抛出异常
        messageRepository = mockk()
        messageQueueRepository = mockk()
        conversationMemberRepository = mockk()
        conversationRepository = mockk()
        friendshipRepository = mockk()
        idGenerator = mockk()
        seqService = mockk<SeqService>()
        coEvery { seqService.nextSeq(any(), any()) } returns 1L
        coEvery { seqService.currentSeq(any(), any()) } returns 1L
        messageService = MessageService(
            messageRepository,
            messageQueueRepository,
            conversationMemberRepository,
            conversationRepository,
            friendshipRepository,
            idGenerator,
            seqService
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ================ sendMessage ================

    /**
     * 发送消息：内容为空时抛出 INVALID_PARAM。
     */
    @Test
    fun sendMessageShouldThrowInvalidParamWhenContentIsBlank() = runTest {
        // 构造请求：content 为空白字符串
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("   ")
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)

        // 验证无任何仓库调用
        coVerify(exactly = 0) {
            conversationMemberRepository.findByConversationIdAndUserId(any(), any())
        }
    }

    /**
     * 发送消息：clientMessageId 为空时抛出 INVALID_PARAM。
     */
    @Test
    fun sendMessageShouldThrowInvalidParamWhenClientMessageIdIsBlank() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("hello")
            .setClientTs(now)
            .setClientMessageId("")
            .build()

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    /**
     * 发送消息：成员记录不存在时抛出 NOT_MEMBER。
     */
    @Test
    fun sendMessageShouldThrowNotMemberWhenMemberIsNull() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("hello")
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
        } returns null

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    /**
     * 发送消息：成员已软删除时抛出 NOT_MEMBER。
     */
    @Test
    fun sendMessageShouldThrowNotMemberWhenMemberIsDeleted() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("hello")
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
        } returns deletedMember()

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    /**
     * 发送消息：会话不存在时抛出 CONV_NOT_FOUND。
     */
    @Test
    fun sendMessageShouldThrowConvNotFoundWhenConversationNotFound() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("hello")
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
        } returns activeMember()

        coEvery { conversationRepository.findById(conversationId) } returns Optional.empty()

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    /**
     * 发送消息：私聊会话且非好友关系时抛出 NOT_FRIEND。
     */
    @Test
    fun sendMessageShouldThrowNotFriendForPrivateConvWhenNotFriends() = runTest {
        val privateConvId = "private:1:2"
        val req = SendMessageReq.newBuilder()
            .setConversationId(privateConvId)
            .setMessageType(ChatContentType.TEXT)
            .setContent("hello")
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(privateConvId, senderUid)
        } returns activeMember(convId = privateConvId)

        coEvery { conversationRepository.findById(privateConvId) } returns Optional.of(privateConversation())
        coEvery { friendshipRepository.findByUserIdAndFriendId(1, 2) } returns null

        val exception = assertFailsWith<ChatException> {
            messageService.sendMessage(req, senderUid)
        }
        assertEquals(BizCode.NOT_FRIEND, exception.bizCode)
    }

    /**
     * 发送消息：群聊正常发送流程。
     * 验证 Snowflake ID 生成、Redis Stream 入队、会话元信息更新。
     */
    @Test
    fun sendMessageShouldGenerateIdEnqueueAndUpdateConversationForGroupConv() = runTest {
        val content = "Hello, World!"
        val req = SendMessageReq.newBuilder()
            .setConversationId(conversationId)
            .setMessageType(ChatContentType.TEXT)
            .setContent(content)
            .setClientTs(now)
            .setClientMessageId("cmid-001")
            .build()

        val conv = groupConversation()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
        } returns activeMember()

        coEvery { conversationRepository.findById(conversationId) } returns Optional.of(conv)
        coEvery { idGenerator.nextId() } returns mockMsgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id-001"
        coEvery { conversationRepository.save(any()) } returns conv

        val result = messageService.sendMessage(req, senderUid)

        // 验证返回结果
        assertEquals(mockMsgId, result.msgId)
        assertEquals(conversationId, result.conversationId)
        assertEquals(senderUid, result.senderUid)
        assertNotNull(result.chatMessage)
        assertEquals(mockMsgId, result.chatMessage.msgId)
        assertEquals(content, result.chatMessage.content)
        assertEquals(conv, result.conversation)

        // 验证会话元信息更新
        assertEquals(mockMsgId, conv.lastMessageId)
        assertEquals(content, conv.lastMessagePreview)
        assertTrue(conv.lastMessageTs > 0)
        assertNotNull(conv.updatedAt)

        // 验证调用链路
        coVerify(exactly = 1) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
            conversationRepository.findById(conversationId)
            idGenerator.nextId()
            messageQueueRepository.enqueue(any())
            conversationRepository.save(conv)
        }
    }

    /**
     * 发送消息：私聊正常发送流程（好友关系通过）。
     */
    @Test
    fun sendMessageShouldSucceedForPrivateConvWithFriends() = runTest {
        val privateConvId = "private:1:2"
        val content = "Hello, friend!"
        val req = SendMessageReq.newBuilder()
            .setConversationId(privateConvId)
            .setMessageType(ChatContentType.TEXT)
            .setContent(content)
            .setClientTs(now)
            .setClientMessageId("cmid-002")
            .build()

        val conv = privateConversation()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(privateConvId, senderUid)
        } returns activeMember(convId = privateConvId)

        coEvery { conversationRepository.findById(privateConvId) } returns Optional.of(conv)
        coEvery { friendshipRepository.findByUserIdAndFriendId(1, 2) } returns
                FriendshipEntity(userId = 1, friendId = 2).apply { deleted = 0 }
        coEvery { idGenerator.nextId() } returns mockMsgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id-002"
        coEvery { conversationRepository.save(any()) } returns conv

        val result = messageService.sendMessage(req, senderUid)

        assertEquals(mockMsgId, result.msgId)
        assertEquals(content, result.chatMessage.content)

        coVerify(exactly = 1) {
            friendshipRepository.findByUserIdAndFriendId(1, 2)
            idGenerator.nextId()
            messageQueueRepository.enqueue(any())
            conversationRepository.save(conv)
        }
    }

    // ================ pullMessages ================

    /**
     * 拉取消息：成员不存在时抛出 NOT_MEMBER。
     */
    @Test
    fun pullMessagesShouldThrowNotMemberWhenMemberNotFound() = runTest {
        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(0L)
            .setLimit(20)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns null

        val exception = assertFailsWith<MessageException> {
            messageService.pullMessages(req, userId)
        }
        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    /**
     * 拉取消息：成员已软删除时抛出 NOT_MEMBER。
     */
    @Test
    fun pullMessagesShouldThrowNotMemberWhenMemberIsDeleted() = runTest {
        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(0L)
            .setLimit(20)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns deletedMember(uid = userId)

        val exception = assertFailsWith<MessageException> {
            messageService.pullMessages(req, userId)
        }
        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    /**
     * 拉取消息：正常拉取返回消息列表，hasMore=false。
     */
    @Test
    fun pullMessagesShouldReturnMessagesWithCursorPagination() = runTest {
        val cursor = 0L
        val limit = 20
        val mockMessages = (1L..3L).map { messageEntity(id = it) }

        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(cursor)
            .setLimit(limit)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns activeMember(uid = userId)

        coEvery {
            messageRepository.findMessagesBackward(conversationId, Long.MAX_VALUE, Pageable.ofSize(limit + 1))
        } returns mockMessages

        val resp = messageService.pullMessages(req, userId)

        // 3 条消息，limit=20，3 <= 20 → hasMore=false
        assertEquals(3, resp.messagesCount)
        assertFalse(resp.hasMore)

        // 验证消息内容
        val msg0 = resp.getMessages(0)
        assertEquals(1L, msg0.msgId)
        assertEquals(conversationId, msg0.conversationId)
        assertEquals(senderUid, msg0.senderUid)
        assertEquals(ChatContentType.TEXT, msg0.messageType)
        assertEquals("msg-1", msg0.content)

        coVerify(exactly = 1) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
            messageRepository.findMessagesBackward(conversationId, Long.MAX_VALUE, Pageable.ofSize(limit + 1))
        }
    }

    /**
     * 拉取消息：游标非 0 时使用请求中的 cursor 值。
     */
    @Test
    fun pullMessagesShouldUseProvidedCursorWhenNonZero() = runTest {
        val cursor = 100L
        val limit = 10
        val mockMessages = (101L..102L).map { messageEntity(id = it) }

        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(cursor)
            .setLimit(limit)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns activeMember(uid = userId)

        coEvery {
            messageRepository.findMessagesBackward(conversationId, cursor, Pageable.ofSize(limit + 1))
        } returns mockMessages

        val resp = messageService.pullMessages(req, userId)

        assertEquals(2, resp.messagesCount)
        assertFalse(resp.hasMore)
    }

    /**
     * 拉取消息：结果超出 limit 时 hasMore=true。
     */
    @Test
    fun pullMessagesShouldSetHasMoreWhenResultsExceedLimit() = runTest {
        val cursor = 0L
        val limit = 5
        // 返回 limit + 1 = 6 条，触发 hasMore
        val mockMessages = (1L..6L).map { messageEntity(id = it) }

        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(cursor)
            .setLimit(limit)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns activeMember(uid = userId)

        coEvery {
            messageRepository.findMessagesBackward(conversationId, Long.MAX_VALUE, Pageable.ofSize(limit + 1))
        } returns mockMessages

        val resp = messageService.pullMessages(req, userId)

        // limit=5，结果 list 共 6 条，应丢掉最后 1 条 → messagesCount=5
        assertEquals(limit, resp.messagesCount)
        assertTrue(resp.hasMore)

        // 验证返回的是前 5 条（ID 1~5），第 6 条被丢弃
        val ids = (0 until limit).map { resp.getMessages(it).msgId }
        assertEquals((1L..5L).toList(), ids)
    }

    /**
     * 拉取消息：空结果返回空响应。
     */
    @Test
    fun pullMessagesShouldReturnEmptyResponseForEmptyResults() = runTest {
        val req = PullMessagesReq.newBuilder()
            .setConversationId(conversationId)
            .setCursor(0L)
            .setLimit(20)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns activeMember(uid = userId)

        coEvery {
            messageRepository.findMessagesBackward(conversationId, Long.MAX_VALUE, Pageable.ofSize(21))
        } returns emptyList()

        val resp = messageService.pullMessages(req, userId)

        assertEquals(0, resp.messagesCount)
        assertFalse(resp.hasMore)
    }

    // ================ readReport ================

    /**
     * 已读报告：成员不存在时抛出 NOT_MEMBER。
     */
    @Test
    fun readReportShouldThrowNotMemberWhenMemberNotFound() = runTest {
        val req = ReadReportReq.newBuilder()
            .setConversationId(conversationId)
            .setLastReadMsgId(100L)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns null

        val exception = assertFailsWith<MessageException> {
            messageService.readReport(req, userId)
        }
        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    /**
     * 已读报告：正常更新已读回执。
     */
    @Test
    fun readReportShouldUpdateReadReceipt() = runTest {
        val lastReadMsgId = 200L
        val req = ReadReportReq.newBuilder()
            .setConversationId(conversationId)
            .setLastReadMsgId(lastReadMsgId)
            .build()

        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        } returns activeMember(uid = userId)

        coEvery {
            conversationMemberRepository.updateReadReceipt(conversationId, userId, lastReadMsgId)
        } just Runs

        messageService.readReport(req, userId)

        coVerify(exactly = 1) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
            conversationMemberRepository.updateReadReceipt(conversationId, userId, lastReadMsgId)
        }
    }
}
