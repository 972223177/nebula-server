package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.ConvListReq
import com.nebula.chat.conversation.ConvListResp
import com.nebula.chat.conversation.ConversationBrief
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 会话列表 Handler — method = "conversation/list"（D-01, D-13, D-21）。
 *
 * 按用户 ID 查询其参与的所有会话，游标分页按 updatedAt 降序返回。
 * 每次最多返回 limit 条，has_more 表示是否有更多数据。
 * 响应包含 ConversationBrief（含 lastReadMsgId 批量查询填充）。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库（用于批量查 lastReadMsgId）
 */
class ListConversationsHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository
) : Handler<ConvListReq, ConvListResp> {

    override val method: String = "conversation/list"

    /** 单页最大返回条数 */
    private val maxLimit = 50

    override suspend fun handle(req: ConvListReq): ConvListResp {
        val session = currentCoroutineContext().requireSession()

        val limit = minOf(req.limit.coerceIn(1, maxLimit), maxLimit)

        // 游标：cursor=0 表示首次查询，否则将 epoch millis 转换为 LocalDateTime
        val cursorDateTime = if (req.cursor == 0L) null
            else LocalDateTime.ofInstant(Instant.ofEpochMilli(req.cursor), ZoneOffset.UTC)

        // 多取一条判断 hasMore（游标分页策略）
        val conversations = withContext(Dispatchers.IO) {
            conversationRepository.findConversationsByUserId(
                userId = session.userId,
                cursor = cursorDateTime,
                pageable = PageRequest.of(0, limit + 1)
            )
        }

        val hasMore = conversations.size > limit
        val result = if (hasMore) conversations.dropLast(1) else conversations

        // 批量查询每个会话的 lastReadMsgId
        val convIds = result.map { it.id!! }
        val memberMap = if (convIds.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                conversationMemberRepository.findByConversationIdsAndUserId(convIds, session.userId)
            }.associateBy { it.conversationId }
        } else {
            emptyMap()
        }

        val builder = ConvListResp.newBuilder()
        result.forEach { entity ->
            val member = memberMap[entity.id]
            builder.addConversations(ConversationBrief.newBuilder()
                .setConversationId(entity.id!!)
                .setType(if (entity.type == 0) "private" else "group")
                .setName(entity.name)
                .setAvatarUrl(entity.avatar)
                .setLastMessageId(entity.lastMessageId)
                .setLastMessagePreview(entity.lastMessagePreview)
                .setLastMessageTs(entity.lastMessageTs)
                .setLastUpdatedAt(entity.updatedAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .setLastReadMsgId(member?.lastReadMessageId ?: 0)
                .build())
        }
        builder.setHasMore(hasMore)
        return builder.build()
    }
}
