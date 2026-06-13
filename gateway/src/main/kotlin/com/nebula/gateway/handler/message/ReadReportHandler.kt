package com.nebula.gateway.handler.message

import com.nebula.chat.Response
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.chat.MessageService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * 已读报告 Handler — method = "message/read"（D-23 ~ D-28）。
 *
 * 职责：
 * - 委托 MessageService 处理已读报告业务逻辑（成员验证、更新已读进度）
 * - 删除 Redis 未读计数键（gateway 层 Redis 操作）
 * - 私聊场景推送已读回执给原发送者（gateway 层推送）
 *
 * @param messageService 消息业务服务
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param pushService 推送服务
 * @param connection Lettuce Redis 连接
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ReadReportHandler(
    private val messageService: MessageService,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>
) : Handler<ReadReportReq, Response> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "message/read"

    companion object {
        private const val PRIVATE_TYPE = 0
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: ReadReportReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 委托 MessageService 处理业务逻辑
        messageService.readReport(req, session.userId)

        // D-28: 删除 Redis 未读计数键（gateway 层 Redis 操作）
        redis.del("conversation:${req.conversationId}:unread:${session.userId}")

        // D-27: 获取会话并判断类型
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(req.conversationId).orElse(null)
        } ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")
        val isPrivate = conversation.type == PRIVATE_TYPE

        // D-23: 私聊场景推送已读回执给原发送者
        if (isPrivate) {
            pushReadReceiptToSender(req, session.userId)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMethod("message/read")
            .build()
    }

    private suspend fun pushReadReceiptToSender(req: ReadReportReq, readerUid: Long) {
        val members = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationId(req.conversationId)
        }
        val senderMember = members.firstOrNull { it.userId != readerUid }

        if (senderMember != null) {
            val payload = ReadReceiptPayload.newBuilder()
                .setConversationId(req.conversationId)
                .setReaderUid(readerUid)
                .setMsgId(req.lastReadMsgId)
                .build()
            pushService.pushReadReceipt(senderMember.userId, payload)
        } else {
            logger.debug { "私聊已读回执跳过：会话=${req.conversationId}，读者=$readerUid，未找到发送者" }
        }
    }
}
