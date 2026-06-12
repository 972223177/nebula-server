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
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlin.coroutines.coroutineContext

/**
 * 已读报告 Handler — method = "message/read"（D-23, D-24, D-25, D-26, D-27, D-28）。
 *
 * 处理客户端上报的已读回执：
 * - 更新 MySQL conversation_members 表的 last_read_message_id 并清零 unread_count（D-24）
 * - 删除 Redis 中对应会话的未读计数键（D-28）
 * - 私聊场景：构建 ReadReceiptPayload 并通过 PushService 推送给原发送者（D-23）
 * - 群聊场景：不推送已读回执（D-23）
 *
 * 安全措施（REVIEW-MEDIUM-10）：
 * - 在更新已读回执前，通过 findByConversationIdAndUserId 验证请求者是会话成员
 * - 非成员时抛出 ConversationException(BizCode.NOT_MEMBER)
 *
 * @param conversationRepository 会话数据仓库，用于获取会话类型和存在性检查
 * @param conversationMemberRepository 会话成员数据仓库，用于成员验证和已读更新
 * @param pushService 推送服务，用于私聊场景推送已读回执
 * @param connection Lettuce Redis 连接，用于删除未读计数键
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ReadReportHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val pushService: PushService,
    private val connection: StatefulRedisConnection<String, String>
) : Handler<ReadReportReq, Response> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "message/read"

    companion object {
        /** 私聊会话类型常量：ConversationEntity.type == 0 表示私聊（D-27） */
        private const val PRIVATE_TYPE = 0

        private val logger = KotlinLogging.logger {}
    }

    /**
     * 处理已读报告请求。
     *
     * 处理流程：
     * 1. 提取 session（由 AuthInterceptor 注入协程上下文）
     * 2. 获取会话并验证存在性（D-27）
     * 3. 验证请求者是会话成员（REVIEW-MEDIUM-10）
     * 4. 更新已读进度：updateReadReceipt() 设置 last_read_message_id 并清零未读计数（D-24）
     * 5. 重置 Redis 未读计数键（D-28）
     * 6. 私聊场景：查询另一方成员并推送 READ_RECEIPT（D-23, D-25）
     *
     * @param req 已读报告请求：含会话ID和最后一条已读消息ID
     * @return 成功响应（code=200, method="message/read"）
     * @throws ConversationException(BizCode.CONV_NOT_FOUND) 会话不存在
     * @throws ConversationException(BizCode.NOT_MEMBER) 非会话成员
     */
    override suspend fun handle(req: ReadReportReq): Response {
        val session = coroutineContext.requireSession()

        // D-27: 获取会话并判断类型（私聊/群聊）
        val conversation = conversationRepository.findById(req.conversationId).orElse(null)
            ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")
        val isPrivate = conversation.type == PRIVATE_TYPE

        // REVIEW-MEDIUM-10: 成员身份检查 — 确保只有会话成员才能更新已读回执
        val member = conversationMemberRepository.findByConversationIdAndUserId(
            req.conversationId, session.userId
        ) ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        // D-24: 更新已读进度 — 设置 last_read_message_id 并清零 unread_count
        conversationMemberRepository.updateReadReceipt(req.conversationId, session.userId, req.lastReadMsgId)

        // D-28: 删除 Redis 未读计数键
        // 接受极低概率竞态：DEL 后新消息 INCR 覆盖，下次新消息自动修复
        redis.del("conversation:${req.conversationId}:unread:${session.userId}")

        // D-23: 私聊场景推送已读回执给原发送者
        if (isPrivate) {
            pushReadReceiptToSender(req, session.userId)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMethod("message/read")
            .build()
    }

    /**
     * 向私聊另一方发送已读回执推送（D-23, D-24, D-25）。
     *
     * 通过 ConversationMemberRepository.findByConversationId() 查询会话所有成员，
     * 排除读者自己后剩下的即为原发送者。如果私聊另一方已退出会话，则不推送并记录 debug 日志。
     *
     * @param req 原始已读报告请求
     * @param readerUid 读者用户 ID（当前请求者）
     */
    private suspend fun pushReadReceiptToSender(req: ReadReportReq, readerUid: Long) {
        // 查询私聊的另一方成员
        val members = conversationMemberRepository.findByConversationId(req.conversationId)
        val senderMember = members.firstOrNull { it.userId != readerUid }

        if (senderMember != null) {
            val payload = ReadReceiptPayload.newBuilder()
                .setConversationId(req.conversationId)
                .setReaderUid(readerUid)
                .setMsgId(req.lastReadMsgId)
                .build()
            // D-25: PushService.pushReadReceipt 使用已有 proto（ReadReceiptPayload）
            pushService.pushReadReceipt(senderMember.userId, payload)
        } else {
            // 私聊另一方已退出会话，不推送
            logger.debug { "私聊已读回执跳过：会话=${req.conversationId}，读者=$readerUid，未找到发送者" }
        }
    }
}
