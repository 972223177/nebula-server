package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * chat/send Handler（D-04, D-05, D-06, D-09, D-11, D-13）。
 *
 * 职责：
 * - 委托 MessageService 处理核心业务逻辑（参数校验、成员验证、好友检查、写入 Redis Stream）
 * - 执行 Redis 去重检查（DedupStep 逻辑，gateway 层 Redis 操作）
 * - 写入后异步 fire-and-forget 执行未读计数递增和推送
 *
 * @param messageService 消息业务服务
 * @param pushService 推送服务（异步 fire-and-forget）
 * @param conversationMemberRepository 会话成员查询（异步未读计数）
 * @param connection Redis 连接（去重 + 未读计数）
 * @param scope 协程作用域（Dispatcher.IO + SupervisorJob）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandler(
    private val messageService: MessageService,
    private val pushService: PushService,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val connection: StatefulRedisConnection<String, String>,
    private val scope: CoroutineScope
) : Handler<SendMessageReq, SendMessageResp> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "chat/send"

    companion object {
        /** 去重 SETNX key 前缀 */
        private const val DEDUP_KEY_PREFIX = "dedup:msg:"
        /** 去重 TTL：7 天 */
        private const val DEDUP_TTL_SECONDS = 7 * 24 * 3600L
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        val senderUid = session.userId

        // Step 1: 去重检查（Redis SETNX — gateway 层 Redis 操作）
        val dedupKey = "$DEDUP_KEY_PREFIX${req.clientMessageId}"
        val isDuplicate = !(redis.setnx(dedupKey, senderUid.toString()) ?: false)
        if (isDuplicate) {
            throw SendMessageException(BizCode.INVALID_PARAM, "重复消息（clientMessageId=${req.clientMessageId}）")
        }
        redis.expire(dedupKey, DEDUP_TTL_SECONDS)

        // Step 2: 委托 MessageService 处理核心业务逻辑
        val result = try {
            messageService.sendMessage(req, senderUid)
        } catch (e: Exception) {
            // 异常时清理去重 key，允许重试
            redis.del(dedupKey)
            throw e
        }

        // Step 3: 构建响应并异步推送
        val response = SendMessageResp.newBuilder()
            .setMsgId(result.msgId)
            .setServerTs(result.serverTs)
            .build()

        // 异步 fire-and-forget：未读计数 + 推送
        scope.launch {
            asyncUnreadAndPush(result)
        }

        return response
    }

    /**
     * 异步执行未读计数递增和消息推送。
     */
    private suspend fun asyncUnreadAndPush(result: SendMessageResult) {
        // 未读计数递增
        try {
            val members = withContext(Dispatchers.IO) {
                conversationMemberRepository
                    .findByConversationId(result.conversationId)
            }.filter { it.userId != result.senderUid }
            for (member in members) {
                try {
                    redis.incr("conversation:${result.conversationId}:unread:${member.userId}")
                } catch (e: Exception) {
                    logger.error(e) { "INCR unread failed for userId=${member.userId}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Unread count batch increment failed for conv=${result.conversationId}" }
        }

        // 推送消息给在线成员
        try {
            pushService.pushMessage(result.conversationId, result.chatMessage, result.senderUid)
        } catch (e: Exception) {
            logger.error(e) { "Async push failed for conv=${result.conversationId}" }
        }
    }
}
