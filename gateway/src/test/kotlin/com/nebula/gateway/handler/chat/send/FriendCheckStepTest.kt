package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.conversation.ConversationConstants.CONV_TYPE_PRIVATE
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendshipRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FriendCheckStep 私聊好友关系校验单元测试（D-56）。
 *
 * 覆盖场景：
 * - 群聊会话 → 跳过检查返回 true
 * - 私聊+好友 → 通过检查返回 true
 * - 私聊+非好友 → 抛出 SendMessageException(NOT_FRIEND)
 * - 私聊+已删除好友 → 抛出 SendMessageException(NOT_FRIEND)
 */
class FriendCheckStepTest {

    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var step: FriendCheckStep

    /** 群聊会话 ID */
    private val groupConvId = "group:abc123"
    /** 私聊会话 ID（格式 private:smaller:larger） */
    private val privateConvId = "private:1001:2001"
    /** 发送者 uid */
    private val senderUid = 1001L

    @BeforeEach
    fun setUp() {
        friendshipRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        step = FriendCheckStep(friendshipRepo, conversationRepo)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：群聊会话 → 跳过检查
    // ═══════════════════════════════════════════════════════════

    @Test
    fun groupChatShouldSkipFriendCheck() = runTest {
        // Given: 会话类型为群聊（type != 1）
        val groupConv = ConversationEntity(type = 2, name = "技术交流群")  // type=2 为群聊
        groupConv.id = groupConvId
        coEvery { conversationRepo.findById(groupConvId) } returns Optional.of(groupConv)

        val req = SendMessageReq.newBuilder()
            .setConversationId(groupConvId)
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When: 执行 Step
        val result = step.execute(context)

        // Then: 群聊直接通过，不检查好友关系
        assertTrue(result, "群聊会话应跳过好友检查返回 true")
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：私聊+好友 → 通过检查
    // ═══════════════════════════════════════════════════════════

    @Test
    fun privateChatWithFriendShouldReturnTrue() = runTest {
        // Given: 私聊会话 + 双方是好友（deleted=0）
        val privateConv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
        privateConv.id = privateConvId
        coEvery { conversationRepo.findById(privateConvId) } returns Optional.of(privateConv)

        val friendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        friendship.deleted = 0
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns friendship

        val req = SendMessageReq.newBuilder()
            .setConversationId(privateConvId)
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When: 执行 Step
        val result = step.execute(context)

        // Then: 好友关系正常，通过检查
        assertTrue(result, "私聊+好友应返回 true")
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：私聊+非好友 → 拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    fun privateChatNonFriendShouldThrowNotFriend() = runTest {
        // Given: 私聊会话，但双方不是好友
        val privateConv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
        privateConv.id = privateConvId
        coEvery { conversationRepo.findById(privateConvId) } returns Optional.of(privateConv)

        // 好友关系不存在
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null

        val req = SendMessageReq.newBuilder()
            .setConversationId(privateConvId)
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When & Then: 应抛出 SendMessageException(NOT_FRIEND)
        val ex = assertFailsWith<SendMessageException> {
            step.execute(context)
        }
        assertEquals(BizCode.NOT_FRIEND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：私聊+已删除好友 → 拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    fun privateChatDeletedFriendShouldThrowNotFriend() = runTest {
        // Given: 私聊会话，好友关系存在但已软删除（deleted=1）
        val privateConv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
        privateConv.id = privateConvId
        coEvery { conversationRepo.findById(privateConvId) } returns Optional.of(privateConv)

        val deletedFriendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        deletedFriendship.deleted = 1
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns deletedFriendship

        val req = SendMessageReq.newBuilder()
            .setConversationId(privateConvId)
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When & Then: 应抛出 SendMessageException(NOT_FRIEND)
        val ex = assertFailsWith<SendMessageException> {
            step.execute(context)
        }
        assertEquals(BizCode.NOT_FRIEND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：会话不存在 → 直接通过（由后续 Step 处理）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun conversationNotFoundShouldReturnTrue() = runTest {
        // Given: 会话不存在
        coEvery { conversationRepo.findById("nonexistent:conv") } returns Optional.empty()

        val req = SendMessageReq.newBuilder()
            .setConversationId("nonexistent:conv")
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When: 执行 Step
        val result = step.execute(context)

        // Then: 会话不存在时直接通过，交由后续 Step 处理
        assertTrue(result, "会话不存在时应返回 true，由后续 Step 处理")
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：私聊 ID 格式异常 → 返回 true（由后续逻辑处理）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun malformedPrivateConvIdShouldReturnTrue() = runTest {
        // Given: 私聊会话，但 ID 格式不符合 private:smaller:larger
        val privateConv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
        privateConv.id = "malformed:conv:id"
        coEvery { conversationRepo.findById("malformed:conv:id") } returns Optional.of(privateConv)

        val req = SendMessageReq.newBuilder()
            .setConversationId("malformed:conv:id")
            .setContent("Hello")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // When: 执行 Step
        val result = step.execute(context)

        // Then: ID 格式异常时直接通过，交由后续逻辑处理
        assertTrue(result, "ID 格式异常时应返回 true，由后续逻辑处理")
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：parsePrivateConvId 工具方法测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parsePrivateConvIdShouldReturnCorrectUidPair() {
        // Given: 标准格式的私聊会话 ID
        val convId = "private:1001:2001"

        // When: 解析
        val result = FriendCheckStep.parsePrivateConvId(convId)

        // Then: 返回正确的 uid 对
        assertEquals(Pair(1001L, 2001L), result)
    }

    @Test
    fun parsePrivateConvIdMismatchShouldReturnNull() {
        // Given: 前缀不是 private 的 ID
        val result = FriendCheckStep.parsePrivateConvId("group:abc123")

        // Then: 返回 null
        assertEquals(null, result)
    }

    @Test
    fun parsePrivateConvIdNonNumericUidShouldReturnNull() {
        // Given: 包含非数字 uid 的 ID
        val result = FriendCheckStep.parsePrivateConvId("private:abc:2001")

        // Then: 返回 null
        assertEquals(null, result)
    }
}
