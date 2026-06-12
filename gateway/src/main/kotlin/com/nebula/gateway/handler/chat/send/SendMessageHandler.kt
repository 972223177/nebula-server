package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.ConversationMemberRepository
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
 * chat/send Handler — Step 链编排器（D-04, D-05, D-06, D-09, D-11, D-13）。
 *
 * 职责：
 * - 编排 Step 链顺序执行：ValidateStep → DedupStep → WriteStep
 * - Step 链包裹 try-catch，非预期异常转化为 SendMessageException（REVIEW-HIGH-2）
 * - WriteStep 完成后立即返回 SendMessageResp（D-04 per REVIEW）
 * - 异步 fire-and-forget 执行未读计数递增和推送（REVIEW-MEDIUM-5）
 *
 * 异步推送设计（D-04 per REVIEW）：
 * - WriteStep 返回后立即构造 SendMessageResp 返回给调用方
 * - 推送和未读计数在独立协程中 fire-and-forget 执行
 * - 推送失败不影响消息落盘和响应返回（D-05）
 *
 * @param steps Step 链实例列表（Validate → Dedup → Write）
 * @param pushService 推送服务（异步 fire-and-forget）
 * @param conversationMemberRepository 会话成员查询（异步未读计数）
 * @param connection Redis 连接（异步未读计数 INCR）
 * @param scope 协程作用域（Dispatcher.IO + SupervisorJob）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandler(
    private val steps: List<SendMessageStep>,
    private val pushService: PushService,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val connection: StatefulRedisConnection<String, String>,
    private val scope: CoroutineScope
) : Handler<SendMessageReq, SendMessageResp> {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = "chat/send"

    /**
     * 处理消息发送请求（D-04, D-13）。
     *
     * 执行流程：
     * 1. 创建 SendContext，获取 Session 中的 senderUid
     * 2. 顺序执行 Step 链（Validate → Dedup → Write）
     * 3. Step 链包裹 try-catch：预期异常直接传播，非预期异常包装（REVIEW-HIGH-2）
     * 4. 构建 SendMessageResp（含 msgId + serverTs）
     * 5. 启动 fire-and-forget 协程执行未读计数递增和推送
     * 6. 立即返回响应（D-04 per REVIEW — 不等待推送）
     *
     * @param req 发送消息请求
     * @return 发送消息响应（含 msg_id、server_ts）
     * @throws SendMessageException 验证/去重失败或非预期异常时
     */
    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        val context = SendContext(req = req, senderUid = session.userId)

        // REVIEW-HIGH-2: Step 链包裹 try-catch，非预期异常转化为 SendMessageException
        try {
            for (step in steps) {
                if (!step.execute(context)) break
            }
        } catch (e: SendMessageException) {
            throw e  // 预期异常直接传播
        } catch (e: Exception) {
            // 非预期异常（如 Redis 连接超时）转化为带上下文的 SendMessageException
            throw SendMessageException(
                BizCode.INTERNAL_ERROR,
                "SendMessage Step chain failed at step ${e.stackTrace.firstOrNull()?.methodName ?: "unknown"}: ${e.message}"
            )
        }

        // WriteStep 必须执行完毕，msgId 必须已设置
        val msgId = requireNotNull(context.msgId) { "WriteStep must execute before building response" }
        val response = SendMessageResp.newBuilder()
            .setMsgId(msgId)
            .setServerTs(System.currentTimeMillis())
            .build()

        // D-04 per REVIEW: 写入后立即返回响应，推送和未读计数异步执行
        scope.launch {
            asyncUnreadAndPush(context)
        }

        return response
    }

    /**
     * 异步执行未读计数递增和消息推送（REVIEW-MEDIUM-5）。
     *
     * 此方法在 WriteStep 完成后单独协程中 fire-and-forget 执行：
     * 1. 对非发送者成员逐个递增未读计数（D-06）
     * 2. 调用 PushService 推送消息给在线成员（D-04, D-09）
     *
     * 所有异常通过 try-catch 捕获并记录日志，不影响主流程。
     *
     * @param context Step 链执行完毕的上下文
     */
    private suspend fun asyncUnreadAndPush(context: SendContext) {
        // D-06, D-16: 未读计数递增（REVIEW-MEDIUM-5 — 从 WriteStep 移出）
        try {
            val members = withContext(Dispatchers.IO) {
                conversationMemberRepository
                    .findByConversationId(context.conversationId)
            }.filter { it.userId != context.senderUid }
            for (member in members) {
                try {
                    redis.incr("conversation:${context.conversationId}:unread:${member.userId}")
                } catch (e: Exception) {
                    logger.error(e) { "INCR unread failed for userId=${member.userId}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Unread count batch increment failed for conv=${context.conversationId}" }
        }

        // D-04 async, D-05 best-effort: 推送消息给在线成员
        val msg = context.chatMessage ?: return  // requireNotNull 已在上层检查
        try {
            pushService.pushMessage(context.conversationId, msg, context.senderUid)
        } catch (e: Exception) {
            logger.error(e) { "Async push failed for conv=${context.conversationId}" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
