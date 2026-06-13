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
 * chat/send Handler（D-04, D-05, D-06, D-09, D-11, D-13, D-72）。
 *
 * 职责：
 * - 委托 MessageService 处理核心业务逻辑（参数校验、成员验证、好友检查、写入 Redis Stream）
 * - 写入后异步 fire-and-forget 执行未读计数递增和推送
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageQueueRepository.checkAndSetDedup() 中，
 * handler 层不再处理去重。
 *
 * @param messageService 消息业务服务
 * @param pushService 推送服务（异步 fire-and-forget）
 * @param conversationMemberRepository 会话成员查询（异步未读计数）
 * @param connection Redis 连接（未读计数 INCR 操作）
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
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        val senderUid = session.userId

        // Step 1: 委托 MessageService 处理核心业务逻辑（D-72 去重已下沉到 repository 层）
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
