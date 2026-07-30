package com.nebula.gateway.fanout

import com.nebula.chat.message.ChatMessage
import com.nebula.common.init.DeadLetterCallback
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.gateway.push.PushService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

/**
 * fan-out 后台消费者（§七 Durable Outbox）。
 *
 * 从 [RedisStreamQueue] 消费「待推送 / 待加未读」事件，执行未读计数自增 + 推送 fan-out。
 * 跑在 serverScope 上（与请求上下文取消链解耦，不随 10s 超时 / 断连取消），
 * server 关闭时 serverScope.cancel() 取消循环，未消费 / 未确认事件留 Stream，重启后重放
 * （未读自增由 MessageService 的 unread_dedup 幂等表保证不双计）—— 对应原 SendMessageHandler
 * fire-and-forget 不可重放的缺陷。
 *
 * 处理语义：
 * - 未读自增经 MessageService（unread_dedup 表 + 单条 SQL）幂等：PEL 重投 / 崩溃重启均不双计、不漏计；
 * - 推送由客户端按 msgId 去重，重投无害；
 * - 毒消息（关键字段缺失 / chatMessage 不可解析 / body 与 chatMessage 字段错位）→ DeadLetterCallback 落死信 + XACK；
 * - 瞬态失败（成员查询 / DB 未读 / 推送异常）→ 不 XACK，留 PEL 下轮重试。
 *
 * @param fanoutQueue fan-out 事件队列（[RedisStreamQueue] 通用端口）
 * @param conversationService 会话成员查询
 * @param messageService 幂等未读计数自增
 * @param pushService 消息推送
 * @param deadLetterCallback 死信桥接（毒消息落库）
 * @param scope 后台作用域（serverScope）
 */
class FanoutWorker(
    private val fanoutQueue: RedisStreamQueue,
    private val conversationService: ConversationService,
    private val messageService: MessageService,
    private val pushService: PushService,
    private val deadLetterCallback: DeadLetterCallback,
    private val scope: CoroutineScope
) {
    private val logger = KotlinLogging.logger {}

    @Volatile
    private var stopped = false

    /** 消费循环 Job（供 [stop] 显式取消，测试可用）。 */
    private var loopJob: Job? = null

    /** 启动消费循环（确保消费者组后挂到 scope）。 */
    suspend fun start() {
        fanoutQueue.ensureConsumerGroup()
        loopJob = scope.launch { loop() }
    }

    /** 停止消费循环（正常关闭由 serverScope.cancel() 触发，此方法供测试显式停止）。 */
    fun stop() {
        stopped = true
        loopJob?.cancel()
    }

    private suspend fun loop() {
        while (!stopped && scope.isActive) {
            delay(500.milliseconds)
            processBatch()
        }
    }

    private suspend fun processBatch() {
        val entries = try {
            fanoutQueue.consumeWithRetry(batchSize = 30, blockMs = 0)
        } catch (e: Exception) {
            logger.error(e) { "fanout 消费失败" }
            return
        }
        for (entry in entries) {
            try {
                // 成功处理 或 毒消息已落死信 → 均确认释放 pending；瞬态失败抛异常 → 不确认留 PEL 重试
                processOne(entry.body)
                fanoutQueue.acknowledge(entry.id)
            } catch (e: Exception) {
                logger.error(e) { "fanout 处理失败（瞬态），留 PEL 重试: id=${entry.id}" }
            }
        }
    }

    /**
     * 处理单条事件。
     * - 关键字段缺失 / chatMessage 不可解析 / body 与 chatMessage 字段错位 → 落死信并 return（不再抛异常，调用方确认）；
     * - 成员查询 / DB 未读 / 推送抛异常 → 向上抛出，调用方留 PEL 重试。
     *
     * @return Unit；毒消息路径内部已落死信并直接返回
     */
    internal suspend fun processOne(body: Map<String, String>) {
        val convId = body["conversationId"]
            ?: run { deadLetterPoison(body, "missing conversationId"); return }
        val msgId = body["msgId"]?.toLongOrNull()
            ?: run { deadLetterPoison(body, "missing/invalid msgId"); return }
        val senderUid = body["senderUid"]?.toLongOrNull()
            ?: run { deadLetterPoison(body, "missing/invalid senderUid"); return }
        val chatMessageBytes = body["chatMessage"]
            ?: run { deadLetterPoison(body, "missing chatMessage"); return }
        val chatMessage = runCatching { ChatMessage.parseFrom(Base64.getDecoder().decode(chatMessageBytes)) }
            .getOrElse { run { deadLetterPoison(body, "invalid chatMessage proto"); return } }

        // 交叉校验：body 字段须与 chatMessage 内部一致，防止错位 proto 把未读加到错误会话 / 人（审查 #4）
        if (chatMessage.conversationId != convId || chatMessage.msgId != msgId || chatMessage.senderUid != senderUid) {
            deadLetterPoison(body, "conversationId/msgId/senderUid mismatch between body and chatMessage")
            return
        }

        val members = conversationService.getConversationMembers(convId)
        val targetUids = members.filter { it.userId != senderUid }.map { it.userId }

        // 未读自增（幂等由 MessageService.unread_dedup 保证；失败抛异常留 PEL 重试，不静默吞没）
        messageService.incrementUnreadCount(convId, msgId, senderUid)

        // 推送（客户端按 msgId 去重，重投无害；推送失败抛异常留 PEL 重试）
        pushService.pushMessageToMembers(targetUids, chatMessage)
    }

    private suspend fun deadLetterPoison(body: Map<String, String>, reason: String) {
        try {
            deadLetterCallback.onUnparseableMessage(body, "fanout: $reason")
        } catch (e: Exception) {
            // 死信落库失败：仍 return（调用方会 XACK 释放 PEL），避免 Stream 无限堆积；
            // 未读因未执行不受影响，符合「死信失败优先释放 pending」策略（与 MessageRepositoryImpl 一致）。
            logger.error(e) { "fanout 毒消息落死信失败: $reason" }
        }
    }
}
