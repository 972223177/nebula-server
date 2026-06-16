package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

/**
 * chat/send Handler（D-04, D-05, D-06, D-09, D-11, D-13, D-72）。
 *
 * 职责：
 * - 委托 MessageService 处理核心业务逻辑（参数校验、成员验证、好友检查、去重、写入 Redis Stream）
 * - 写入后异步 fire-and-forget 执行未读计数递增和推送
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageService.checkAndSetDedup() 中，
 * handler 层不再处理去重。
 *
 * @param messageService 消息业务服务（去重 + 写入 + 未读计数）
 * @param pushService 推送服务（异步 fire-and-forget）
 * @param conversationService 会话业务服务（成员查询）
 * @param connection Redis 连接（未读计数 INCR 操作）
 * @param scope 协程作用域（Dispatcher.IO + SupervisorJob，D-85）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandler(
    private val messageService: MessageService,
    private val pushService: PushService,
    private val conversationService: ConversationService,
    private val connection: StatefulRedisConnection<String, String>,
    private val scope: CoroutineScope
) : Handler<SendMessageReq, SendMessageResp> {

    /** Lettuce Redis 协程命令接口，由 connection.reactive() 构建 */
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "chat/send"

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        val senderUid = session.userId

        // M17/M20: 去重检查 — 相同 clientMessageId 重复发送时返回幂等 ACK
        if (req.clientMessageId.isNotEmpty()) {
            val isNew = messageService.checkAndSetDedup(req.clientMessageId, senderUid)
            if (!isNew) {
                logger.info { "检测到重复消息: clientMessageId=${req.clientMessageId}, senderUid=$senderUid" }
                return SendMessageResp.newBuilder()
                    .setMsgId(0L)
                    .setServerTs(System.currentTimeMillis())
                    .setSeq(0L)
                    .build()
            }
        }

        return try {
            // Step 1: 委托 MessageService 处理核心业务逻辑
            val result = messageService.sendMessage(req, senderUid)

            // Step 2: 构建响应（含服务端分配的 seq）
            val response = SendMessageResp.newBuilder()
                .setMsgId(result.msgId)
                .setServerTs(result.serverTs)
                .setSeq(result.seq)
                .build()

            // Step 3: 异步 fire-and-forget：未读计数 + 推送
            scope.launch {
                asyncUnreadAndPush(result)
            }

            response
        } catch (e: BizException) {
            // D-09: Step 链业务异常直接传播，不吞没
            throw e
        } catch (e: Exception) {
            // REVIEW-HIGH-2: 非预期异常包装为 INTERNAL_ERROR，避免泄漏内部细节
            logger.error(e) { "Unexpected error in chat/send, senderUid=$senderUid" }
            throw BizException(BizCode.INTERNAL_ERROR, "Unexpected error: ${e.message}")
        }
    }

    /**
     * 异步执行未读计数递增和消息推送。
     *
     * M29: 合并 pushMessage 和 incrementUnreadCount 的两次成员查询为一次（通过 conversationService）。
     * M24: 同步调用 messageService.incrementUnreadCount() 将未读计数持久化到 DB。
     *
     * @param result 消息发送结果，包含 conversationId、chatMessage、senderUid 等信息
     */
    private suspend fun asyncUnreadAndPush(result: SendMessageResult) {
        try {
            // M29: 一次查询获取成员列表，复用给未读计数和推送
            val members = conversationService.getConversationMembers(result.conversationId)
            val targetUids = members.filter { it.userId != result.senderUid }.map { it.userId }

            // M24: 未读计数持久化到 DB（JPQL 批量 UPDATE）
            try {
                messageService.incrementUnreadCount(result.conversationId, result.senderUid)
            } catch (e: Exception) {
                logger.error(e) { "DB unread count increment failed for conv=${result.conversationId}" }
            }

            // Redis 未读计数递增
            for (uid in targetUids) {
                try {
                    redis.incr("conversation:${result.conversationId}:unread:$uid")
                } catch (e: Exception) {
                    logger.error(e) { "INCR unread failed for userId=$uid" }
                }
            }

            // M29: 使用预查询的 userId 列表推送，避免 pushMessage 二次查询 DB
            pushService.pushMessageToMembers(targetUids, result.chatMessage)
        } catch (e: Exception) {
            logger.error(e) { "Unread count batch increment failed for conv=${result.conversationId}" }
        }
    }
}
