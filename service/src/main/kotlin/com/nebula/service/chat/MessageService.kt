package com.nebula.service.chat

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.chat.conversation.ConversationBrief
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ChatException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.util.toEpochMillis
import com.nebula.service.conversation.toConversationBrief
import com.nebula.service.sequence.SeqService
import com.nebula.repository.dao.ConversationDao
import com.nebula.repository.dao.ConversationMemberDao
import com.nebula.repository.dao.FriendshipDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.MessageDao
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.entity.isActive
import com.nebula.repository.redis.MessageQueueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime

/**
 * 消息业务服务（D-04 ~ D-06, D-09, D-11, D-13, D-15, D-74, D-78）。
 *
 * 提供消息发送、拉取、已读报告等核心业务逻辑。
 * 消息发送流程：参数校验 → 成员身份验证 → 好友关系检查（私聊） → 去重 → 写入 Redis Stream。
 * D-78：序列号生成统一委托 [SeqService.nextSeq]（Phase 10 W2），不再直接操作 Redis。
 * 不依赖网关层组件（PushService、SessionRegistry 等），推送由调用方（Handler）负责。
 */
class MessageService(
    private val messageDao: MessageDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val conversationDao: ConversationDao,
    private val friendshipDao: FriendshipDao,
    private val txRunner: JpaTxRunner,
    private val messageQueueRepository: MessageQueueRepository,
    private val idGenerator: SnowflakeIdGenerator,
    private val seqService: SeqService
) {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
        /** 私聊会话类型常量（CQ-12: 1=私聊，与 SQL DDL 一致） */
        private const val CONV_TYPE_PRIVATE = 1
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
     * 4. 生成 Snowflake ID
     * 5. 构造 ChatMessage 写入 Redis Stream
     * 6. 更新会话元信息（lastMessageId、lastMessagePreview 等）
     * 7. 生成会话序列号
     *
     * ## 双写一致性（D-04 修复：写顺序反转）
     *
     * **当前顺序：先写入 Redis Stream（enqueue）→ 再更新 DB 会话元信息（事务内）。**
     *
     * 旧顺序（先 DB 后 Redis）的致命缺陷：DB 元信息更新成功 → Redis enqueue 失败 →
     * 会话概要"超前"指向新 msgId，但实际消息不在 Stream，永久丢失（且因 clientMessageId
     * 幂等，重试时不再 enqueue），表现为「会话列表有概要、message/pull 却为空」。
     *
     * 反转后的权衡：
     * - Redis enqueue 失败 → 抛异常 → 客户端重试 → 无副作用（DB 未动）
     * - Redis 成功 → DB 元信息失败 → 消息已在 Stream 会落库，**可正常拉取**；
     *   仅会话概要可能缺这一条，下次发送即修正。消息不丢，是更优的不变量。
     *
     * 前置只读校验（成员身份 + 私聊好友关系）在 enqueue 之前完成，避免非法消息入 Stream。
     *
     * 更严格的方案是 Transactional Outbox（写 outbox 表 + 异步 Relay 投递），
     * 当前顺序反转已在「消息不丢」与实现复杂度间取得平衡。
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

        // Step 2-3: 前置只读校验成员身份 + 私聊好友关系（enqueue 前护栏，避免非法消息入 Stream）
        val msgId = idGenerator.nextId()
        val now = System.currentTimeMillis()
        val preview = req.content.take(100)
        val nowDate = LocalDateTime.now()

        txRunner.execute { em ->
            val member = conversationMemberDao.findByConversationIdAndUserId(em, conversationId, senderUid)
            if (member == null || !member.isActive) {
                throw ChatException(BizCode.NOT_MEMBER, "用户不是会话成员")
            }

            val conv = conversationDao.findById(em, conversationId)
                ?: throw ChatException(BizCode.CONV_NOT_FOUND)

            if (conv.type == CONV_TYPE_PRIVATE) {
                val friendCheck = checkFriendshipForPrivateConv(em, conversationId, senderUid)
                if (!friendCheck) {
                    throw ChatException(BizCode.NOT_FRIEND, "私聊消息需要好友关系")
                }
            }
        }

        // Step 4: 先构造并 enqueue 到 Redis Stream（保证消息可落库，先于会话元信息更新，
        // 消除「概要超前、消息丢失」漂移）。enqueue 失败抛异常，无副作用，客户端重试。
        val chatMessage = ChatMessage.newBuilder()
            .setMsgId(msgId)
            .setConversationId(conversationId)
            .setSenderUid(senderUid)
            .setMessageType(req.messageType)
            .setContent(req.content)
            .setClientTs(req.clientTs)
            .setServerTs(now)
            .build()

        // 写入 Redis Stream（key 必须与 MessageRepositoryImpl.parseToEntity() 对齐）
        // 修复：parseToEntity 读取 body["id"]，此处原误用 "msgId" 导致消息 ID 为 null、
        // DB INSERT 失败（PK not null），消息永久留在 Redis Stream 无法落库
        val streamFields = mapOf(
            "id" to msgId.toString(),
            "conversationId" to conversationId,
            "senderUid" to senderUid.toString(),
            "messageType" to req.messageType.toString(),
            "content" to req.content,
            "clientMessageId" to req.clientMessageId,
            "clientTs" to req.clientTs.toString(),
            "serverTs" to now.toString(),
            "payload" to (if (req.payload.size() > 0) java.util.Base64.getEncoder().encodeToString(req.payload.toByteArray()) else "")
        )
        messageQueueRepository.enqueue(streamFields)

        // Step 5: 事务内更新会话元信息（消息已在 Stream，即使本事务失败消息仍会落库、可拉取）
        // 托管实体直接改字段，commit 时脏检查自动 flush
        txRunner.execute { em ->
            val conv = conversationDao.findById(em, conversationId)
                ?: throw ChatException(BizCode.CONV_NOT_FOUND)
            conv.lastMessageId = msgId
            conv.lastMessagePreview = preview
            conv.lastMessageTs = now
            conv.updatedAt = nowDate
        }

        // Step 7: 生成会话序列号（D-74 per-(conv,uid) 自增，D-78 统一委托 SeqService）
        val seq = seqService.nextSeq(conversationId, senderUid)

        // 重新加载 conversation 用于返回（因为上面 conv 已在事务内修改/关闭）
        val conversation = txRunner.execute { em -> conversationDao.findById(em, conversationId)!! }

        return SendMessageResult(
            msgId = msgId,
            serverTs = now,
            conversationId = conversationId,
            senderUid = senderUid,
            chatMessage = chatMessage,
            conversationBrief = conversation.toConversationBrief(displayName = conversation.name),
            seq = seq
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
        val limit = req.limit.coerceIn(1, 100)
        val cursor = if (req.cursor == 0L) Long.MAX_VALUE else req.cursor

        val messages = txRunner.execute { em ->
            // 验证成员身份
            val member = conversationMemberDao.findByConversationIdAndUserId(em, conversationId, userId)
            if (member == null || !member.isActive) {
                throw ChatException(BizCode.NOT_MEMBER, "用户不是会话成员")
            }

            messageDao.findMessagesBackward(em, conversationId, cursor, limit + 1)
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

        // 验证成员身份 + 更新已读回执
        txRunner.execute { em ->
            val member = conversationMemberDao.findByConversationIdAndUserId(em, conversationId, userId)
            if (member == null || !member.isActive) {
                throw ChatException(BizCode.NOT_MEMBER, "用户不是会话成员")
            }

            // 更新已读回执
            conversationMemberDao.updateReadReceipt(
                em,
                conversationId = conversationId,
                userId = userId,
                lastReadMsgId = lastReadMsgId
            )
        }
    }

    /**
     * 统计指定会话的消息数量（从数据库查询）。
     *
     * @param conversationId 会话 ID
     * @return 消息总数
     */
    suspend fun countByConversationId(conversationId: String): Long {
        return txRunner.execute { em ->
            messageDao.countByConversationId(em, conversationId)
        }
    }

    /**
     * 消息去重检查 — 使用 Redis SETNX 检测重复消息。
     *
     * @param clientMessageId 客户端消息 ID
     * @param senderUid 发送者用户 ID
     * @return true 表示新消息，false 表示重复
     */
    suspend fun checkAndSetDedup(clientMessageId: String, senderUid: Long): Boolean {
        return messageQueueRepository.checkAndSetDedup(clientMessageId, senderUid)
    }

    /**
     * 递增会话中除发送者外的所有成员的未读计数。
     *
     * @param conversationId 会话 ID
     * @param senderUid 发送者用户 ID（该用户不递增未读）
     */
    suspend fun incrementUnreadCount(conversationId: String, senderUid: Long) {
        txRunner.execute { em ->
            conversationMemberDao.incrementUnreadCount(em, conversationId, senderUid)
        }
    }

    /**
     * 私聊会话的好友关系检查（D-56）。
     *
     * @param conversationId 会话 ID（格式 "private:smaller:larger"）
     * @param senderUid 发送者 UID
     * @return true=是好友，false=非好友
     */
    private suspend fun checkFriendshipForPrivateConv(
        em: jakarta.persistence.EntityManager,
        conversationId: String,
        senderUid: Long
    ): Boolean {
        // 从 "private:smaller:larger" 格式提取两个 UID
        val parts = conversationId.split(":")
        if (parts.size != 3) return true // 非标准格式，放行

        val smaller = parts[1].toLongOrNull() ?: return true
        val larger = parts[2].toLongOrNull() ?: return true

        val friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
        return friendship != null && friendship.isActive
    }

    /**
     * 将 MessageEntity 转换为 ChatMessage Protobuf。
     *
     * @return 转换后的 ChatMessage Protobuf 对象
     */
    private fun MessageEntity.toChatMessage(): ChatMessage {
        val builder = ChatMessage.newBuilder()
            .setMsgId(requireNotNull(id) { "MessageEntity.id 不应为null" })
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
 * @param seq 会话序列号（D-74 per-(conv,uid) 自增）
 */
data class SendMessageResult(
    val msgId: Long,
    val serverTs: Long,
    val conversationId: String,
    val senderUid: Long,
    val chatMessage: ChatMessage,
    /**
     * 序列化后的会话 Brief（懒加载响应字段），由 MessageService 内部构造。
     * gateway 层不直接接触 ConversationEntity，通过此字段获取响应。
     * 2026-07 改造新增。
     */
    val conversationBrief: ConversationBrief,
    val seq: Long = 0,
    /**
     * 是否本次发送触发了私聊懒加载（createPrivateConversation 真正执行了创建/恢复）。
     * true 时 SendMessageResp.conversation 字段应填入会话 Brief，客户端无需再调 conversation/list。
     * false 时该字段保持未设置，客户端继续走原有逻辑。
     * 2026-07 改造新增。
     */
    val conversationCreated: Boolean = false
)
