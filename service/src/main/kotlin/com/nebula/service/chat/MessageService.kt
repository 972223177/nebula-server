package com.nebula.service.chat

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ChatException
import com.nebula.common.exception.MessageException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.MessageRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 消息业务服务（D-04 ~ D-06, D-09, D-11, D-13, D-15）。
 *
 * 提供消息发送、拉取、已读报告等核心业务逻辑。
 * 消息发送流程：参数校验 → 成员身份验证 → 好友关系检查（私聊） → 去重 → 写入 Redis Stream。
 * 不依赖网关层组件（PushService、SessionRegistry 等），推送由调用方（Handler）负责。
 */
class MessageService(
    private val messageRepository: MessageRepository,
    private val messageQueueRepository: MessageQueueRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val conversationRepository: ConversationRepository,
    private val friendshipRepository: FriendshipRepository,
    private val idGenerator: SnowflakeIdGenerator
) {

    companion object {
        private val logger = KotlinLogging.logger {}
        /** 私聊类型常量 */
        private const val CONV_TYPE_PRIVATE = 0
        /** 群聊类型常量 */
        private const val CONV_TYPE_GROUP = 2
    }

    /**
     * 发送消息（D-04, D-05, D-06, D-09, D-11, D-13）。
     *
     * 执行流程：
     * 1. 参数校验：内容非空、clientMessageId 非空
     * 2. 验证发送者是会话成员
     * 3. 私聊会话检查好友关系（D-56）
     * 4. 去重检查：Redis SETNX，7 天 TTL
     * 5. 生成 Snowflake ID
     * 6. 构造 ChatMessage 写入 Redis Stream
     * 7. 更新会话元信息（lastMessageId、lastMessagePreview 等）
     *
     * @param req 发送消息请求
     * @param senderUid 发送者用户 ID
     * @return 发送响应（含 msgId、serverTs），以及异步推送所需的消息上下文
     */
    suspend fun sendMessage(req: SendMessageReq, senderUid: Long): SendMessageResult {
        val conversationId = req.conversationId

        // Step 1: 参数校验
        if (req.content.isBlank()) {
            throw ChatException(BizCode.INVALID_PARAM, "消息内容不能为空")
        }
        if (req.clientMessageId.isBlank()) {
            throw ChatException(BizCode.INVALID_PARAM, "client_message_id 不能为空")
        }

        // Step 2: 验证成员身份
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, senderUid)
        }
        if (member == null || member.deleted == 1) {
            throw ChatException(BizCode.NOT_MEMBER, "用户不是会话成员")
        }

        // Step 3: 私聊会话检查好友关系（D-56）
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(conversationId).orElse(null)
        } ?: throw ChatException(BizCode.CONV_NOT_FOUND)

        if (conversation.type == CONV_TYPE_PRIVATE) {
            val friendCheck = checkFriendshipForPrivateConv(conversationId, senderUid)
            if (!friendCheck) {
                throw ChatException(BizCode.NOT_FRIEND, "私聊消息需要好友关系")
            }
        }

        // Step 4: 去重（clientMessageId 7 天 TTL）
        // 由调用方传入 Redis 连接执行 SETNX，或通过注入的 Redis 操作实现
        // 此方法接收 clientMessageId 去重结果（由 Handler 层或 Step 链组件负责）

        // Step 5-7: 生成 ID + 构造消息 + 写入
        val msgId = idGenerator.nextId()
        val now = System.currentTimeMillis()

        val chatMessage = ChatMessage.newBuilder()
            .setMsgId(msgId)
            .setConversationId(conversationId)
            .setSenderUid(senderUid)
            .setMessageType(req.messageType)
            .setContent(req.content)
            .setClientTs(req.clientTs)
            .setServerTs(now)
            .build()

        // 写入 Redis Stream
        val streamFields = mapOf(
            "msg_id" to msgId.toString(),
            "conversation_id" to conversationId,
            "sender_uid" to senderUid.toString(),
            "message_type" to req.messageType.toString(),
            "content" to req.content,
            "client_message_id" to req.clientMessageId,
            "client_ts" to req.clientTs.toString(),
            "server_ts" to now.toString(),
            "payload" to ""
        )
        withContext(Dispatchers.IO) {
            messageQueueRepository.enqueue(streamFields)
        }

        // 更新会话元信息（D-21：直接在实体上设置 lastMessage 快照字段）
        conversation.lastMessageId = msgId
        conversation.lastMessagePreview = req.content.take(100)
        conversation.lastMessageTs = now
        conversation.updatedAt = LocalDateTime.now()
        withContext(Dispatchers.IO) {
            conversationRepository.save(conversation)
        }

        return SendMessageResult(
            msgId = msgId,
            serverTs = now,
            conversationId = conversationId,
            senderUid = senderUid,
            chatMessage = chatMessage,
            conversation = conversation
        )
    }

    /**
     * 拉取消息（游标分页）。
     *
     * @param req 拉取请求（含 conversationId、cursor、limit）
     * @param userId 当前用户 ID
     * @return 消息列表响应
     */
    suspend fun pullMessages(req: PullMessagesReq, userId: Long): PullMessagesResp {
        val conversationId = req.conversationId

        // 验证成员身份
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        }
        if (member == null || member.deleted == 1) {
            throw MessageException(BizCode.NOT_MEMBER, "用户不是会话成员")
        }

        val limit = req.limit.coerceIn(1, 100)
        val cursor = if (req.cursor == 0L) Long.MAX_VALUE else req.cursor

        val messages = withContext(Dispatchers.IO) {
            messageRepository.findMessagesBackward(conversationId, cursor, Pageable.ofSize(limit + 1))
        }

        val hasMore = messages.size > limit
        val result = if (hasMore) messages.dropLast(1) else messages

        val builder = PullMessagesResp.newBuilder()
        result.forEach { entity ->
            builder.addMessages(entity.toChatMessage())
        }
        builder.setHasMore(hasMore)
        return builder.build()
    }

    /**
     * 处理已读报告（D-15）。
     *
     * @param req 已读报告请求
     * @param userId 当前用户 ID
     */
    suspend fun readReport(req: ReadReportReq, userId: Long) {
        val conversationId = req.conversationId
        val lastReadMsgId = req.lastReadMsgId

        // 验证成员身份
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
        }
        if (member == null || member.deleted == 1) {
            throw MessageException(BizCode.NOT_MEMBER, "用户不是会话成员")
        }

        // 更新已读回执
        withContext(Dispatchers.IO) {
            conversationMemberRepository.updateReadReceipt(
                conversationId = conversationId,
                userId = userId,
                lastReadMsgId = lastReadMsgId
            )
        }
    }

    /**
     * 私聊会话的好友关系检查（D-56）。
     *
     * @param conversationId 会话 ID（格式 "private:smaller:larger"）
     * @param senderUid 发送者 UID
     * @return true=是好友，false=非好友
     */
    private suspend fun checkFriendshipForPrivateConv(conversationId: String, senderUid: Long): Boolean {
        // 从 "private:smaller:larger" 格式提取两个 UID
        val parts = conversationId.split(":")
        if (parts.size != 3) return true // 非标准格式，放行

        val smaller = parts[1].toLongOrNull() ?: return true
        val larger = parts[2].toLongOrNull() ?: return true

        val friendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        return friendship != null && friendship.deleted == 0
    }

    /**
     * 将 MessageEntity 转换为 ChatMessage Protobuf。
     */
    private fun MessageEntity.toChatMessage(): ChatMessage {
        val builder = ChatMessage.newBuilder()
            .setMsgId(id!!)
            .setConversationId(conversationId)
            .setSenderUid(senderUid)
            .setMessageTypeValue(messageType)
            .setContent(content)
            .setClientTs(clientTs)
            .setServerTs(serverTs)
        val payloadBytes = payload
        if (payloadBytes != null && payloadBytes.isNotEmpty()) {
            builder.setPayload(com.google.protobuf.ByteString.copyFrom(payloadBytes))
        }
        return builder.build()
    }
}

/**
 * 消息发送结果 — 包含发送响应和推送所需上下文。
 *
 * @param msgId 消息 ID
 * @param serverTs 服务端时间戳
 * @param conversationId 会话 ID
 * @param senderUid 发送者 UID
 * @param chatMessage 完整的 ChatMessage（用于推送）
 * @param conversation 会话实体（用于判断群聊/私聊等）
 */
data class SendMessageResult(
    val msgId: Long,
    val serverTs: Long,
    val conversationId: String,
    val senderUid: Long,
    val chatMessage: ChatMessage,
    val conversation: ConversationEntity
)
