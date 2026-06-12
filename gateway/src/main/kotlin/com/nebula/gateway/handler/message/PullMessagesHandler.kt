// SECURITY(FIXME Phase 7): 需要补充会话成员检查。
// 当前实现不验证请求者是否为会话成员，任何认证用户可以拉取任何会话的消息。
// 待 Phase 7 Conversation 阶段实现后，需在 handle() 开头添加：
//   conversationMemberRepository.findByConversationIdAndUserId(req.conversationId, session.userId)
//     ?: throw ConversationException(BizCode.NOT_MEMBER)
package com.nebula.gateway.handler.message

import com.nebula.chat.ChatContentType
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.MessageRepository
import com.google.protobuf.ByteString
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.data.domain.Pageable

/**
 * 消息拉取 Handler — method = "message/pull"（D-17, D-18, D-19, D-20, D-21）。
 *
 * 按会话游标分页拉取历史消息（MySQL 游标查询）：
 * - 数据源：MySQL 游标查询，利用 `idx_conv_messages(conversation_id, id)` 索引（D-17）
 * - 翻页方向：tail 优先 + 往前翻。cursor=0 代表最新消息，cursor>0 代表比 cursor 更旧的 limit 条（D-18）
 * - 分页大小：默认 20 条，最大 100 条（D-19）
 * - direction 字段保留不动，Phase 10 间隙检测时使用 forward 方向（D-20）
 * - ChatMessage 不包含发送者用户名/头像信息，客户端通过 user/batchGet 获取（D-21）
 *
 * 安全注释（REVIEW-HIGH-3, T-06-10）：
 * 当前不校验拉取者是否为会话成员。文件开头已添加 `// SECURITY(FIXME Phase 7)` 标记。
 * Phase 7 需要在 handle() 开头补充会话成员检查。
 *
 * @param messageRepository 消息数据仓库，提供游标分页查询
 * @param conversationRepository 会话数据仓库，用于会话存在性检查（REVIEW-MEDIUM-9）
 */
class PullMessagesHandler(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository
) : Handler<PullMessagesReq, PullMessagesResp> {

    override val method: String = "message/pull"

    /**
     * 拉取会话历史消息。
     *
     * 处理流程：
     * 1. 提取请求中的 session（由 AuthInterceptor 注入协程上下文）
     * 2. 会话存在性检查：通过 conversationRepository.existsById() 验证会话是否存在（REVIEW-MEDIUM-9）
     * 3. cursor 特殊处理：cursor=0 时使用 Long.MAX_VALUE 作为有效游标（D-18, Pitfall 2）
     * 4. limit 范围约束：使用 .coerceIn(1, 100) 防止零大小查询和过大结果集（D-19, T-06-08）
     * 5. 执行游标分页查询：messageRepository.findMessagesBackward()
     * 6. Entity → ChatMessage 映射：包含 messageType 的 UNRECOGNIZED 保底处理（REVIEW-MEDIUM-11）
     *
     * @param req 拉取消息请求：含会话ID、游标、分页大小
     * @return 消息列表 + hasMore 标志
     * @throws ConversationException(BizCode.CONV_NOT_FOUND) 会话不存在
     */
    override suspend fun handle(req: PullMessagesReq): PullMessagesResp {
        val session = currentCoroutineContext().requireSession()

        // REVIEW-MEDIUM-9: 会话存在性检查 — 区分"会话不存在"和"会话无消息"
        val exists = withContext(Dispatchers.IO) { conversationRepository.existsById(req.conversationId) }
        if (!exists) {
            throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")
        }

        val cursor = req.cursor
        // D-19, T-06-08: limit 范围约束，默认 20 条，最大 100 条
        val limit = req.limit.coerceIn(1, 100)

        // D-18, Pitfall 2: cursor=0 代表"获取最新消息"
        // Snowflake ID 始终 < Long.MAX_VALUE，所以 MAX_VALUE 等效于"无上界"
        val effectiveCursor = if (cursor == 0L) Long.MAX_VALUE else cursor

        val messages = withContext(Dispatchers.IO) {
            messageRepository.findMessagesBackward(
                req.conversationId, effectiveCursor, Pageable.ofSize(limit)
            )
        }

        // hasMore: 返回数量等于或超过 limit 时，表示还有更多数据
        val hasMore = messages.size >= limit

        return PullMessagesResp.newBuilder()
            .addAllMessages(messages.map { it.toChatMessage() })
            .setHasMore(hasMore)
            .build()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * MessageEntity → ChatMessage 映射扩展函数（D-21, REVIEW-MEDIUM-11）。
 *
 * 字段映射说明：
 * - receiver_uid: 历史消息拉取时统一填 0（MessageEntity 中未持久化 receiver_uid 字段）
 * - messageType: 使用 `?: ChatContentType.UNRECOGNIZED` 处理 null messageType（REVIEW-MEDIUM-11）
 */
private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage.newBuilder()
    .setMsgId(this.id!!)
    .setConversationId(this.conversationId)
    .setSenderUid(this.senderUid)
    .setReceiverUid(0L)
    .setMessageType(ChatContentType.forNumber(this.messageType) ?: ChatContentType.UNRECOGNIZED)
    .setContent(this.content)
    .setPayload(ByteString.copyFrom(this.payload ?: ByteArray(0)))
    .setClientTs(this.clientTs)
    .setServerTs(this.serverTs)
    .build()
