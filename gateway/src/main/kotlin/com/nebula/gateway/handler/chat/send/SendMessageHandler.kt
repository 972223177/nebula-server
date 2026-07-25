package com.nebula.gateway.handler.chat.send
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

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
 * @param sensitiveWordService 敏感词服务（发送前内容脱敏，含敏感词则替换为 * 后照常发送）
 * @param messageService 消息业务服务（去重 + 写入 + 未读计数）
 * @param pushService 推送服务（异步 fire-and-forget）
 * @param conversationService 会话业务服务（成员查询）
 * @param connection Redis 连接（未读计数 INCR 操作）
 * @param serverScope 服务级后台作用域，用于 fire-and-forget 推送，独立于请求上下文（不受 10s 超时/断连取消）
 *
 * 2026-07 review P1 修复：恢复 asyncUnreadAndPush 的 fire-and-forget 语义，挂到 serverScope。
 * 原 D-85 改为内联 suspend 调用，会使响应耗时包含推送 fan-out，且被 Dispatcher 的 10s withTimeout
 * 取消链波及（连接断开/超时直接中断推送），收件人收不到消息。serverScope 不在该取消链上。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandler(
    private val sensitiveWordService: SensitiveWordService,
    private val messageService: MessageService,
    private val pushService: PushService,
    private val conversationService: ConversationService,
    private val connection: StatefulRedisConnection<String, String>,
    private val serverScope: CoroutineScope
) : Handler<SendMessageReq, SendMessageResp> {

    /** Lettuce Redis 协程命令接口，由 connection.reactive() 构建 */
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    override val method: String = MethodNames.Chat.SEND

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: SendMessageReq): SendMessageResp {
        val session = currentCoroutineContext().requireSession()
        val senderUid = session.userId

        // 敏感词脱敏（D-119）：文本内容含敏感词则替换为 *，照常发送，避免违规内容入库与扩散。
        // 仅对非空文本内容检测；图片等非文本消息 content 通常为空，跳过。
        // 放在去重之前：脱敏后的内容参与去重与后续入库，保证落库与推送均为脱敏结果。
        var effectiveReq = req
        if (req.content.isNotBlank()) {
            val filtered = sensitiveWordService.filter(req.content)
            if (filtered != req.content) {
                logger.info { "消息含敏感词已脱敏: senderUid=$senderUid" }
                effectiveReq = req.toBuilder().setContent(filtered).build()
            }
        }

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
            // Step 0: 私聊懒加载 — target_uid != 0 时触发 createPrivateConversation
            //         （群聊场景忽略 target_uid，群聊不支持懒加载：不在群 → 不允许凭空发消息）
            //         返回 (resolvedConvId, didCreate)：
            //         - resolvedConvId 兜底回填给客户端（懒加载时客户端可能没传 convId）
            //         - didCreate=true 时 SendMessageResp.conversation 字段会填入会话 Brief
            //
            // 2026-07 review 遗漏 #4 优化：仅在懒加载实际触发了 createPrivateConversation 时
            //                         重新构造 req（toBuilder 涉及 proto 内部对象分配）；
            //                         常规路径（target_uid=0）直接传原 req，避免内存毛刺。
            val (resolvedConvId, didCreate) = ensurePrivateConversationIfNeeded(effectiveReq, senderUid)
            effectiveReq = if (resolvedConvId != null) {
                effectiveReq.toBuilder().setConversationId(resolvedConvId).build()
            } else {
                effectiveReq
            }

            // Step 1: 委托 MessageService 处理核心业务逻辑
            val result = messageService.sendMessage(
                req = effectiveReq,
                senderUid = senderUid
            ).copy(conversationCreated = didCreate)

            // Step 2: 构建响应（含服务端分配的 seq、懒加载会话元信息）
            val responseBuilder = SendMessageResp.newBuilder()
                .setMsgId(result.msgId)
                .setServerTs(result.serverTs)
                .setSeq(result.seq)
                .setConversationId(result.conversationId)

            // 仅懒加载触发了 createPrivateConversation 时填 conversation 字段，
            // 已有会话直接发的场景保持字段未设置，客户端用 conversation_id 自行拉详情
            if (didCreate) {
                responseBuilder.conversation = result.conversationBrief
            }

            val response = responseBuilder.build()

            // Step 3: 未读计数 + 推送。2026-07 review P1：挂到 serverScope 做 fire-and-forget，
            // 与响应解耦，且不随请求上下文的 10s 超时 / 连接断开被取消。
            serverScope.launch { asyncUnreadAndPush(result) }

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
            logger.error(e) {
                "未读计数或推送异步操作失败: msgId=${result.msgId}, convId=${result.conversationId}, " +
                    "senderUid=${result.senderUid}"
            }
            // REVIEW: 消息已写入 Stream 但推送/未读可能未完成。
            // DeadLetterCallback 当前仅处理消息持久化失败，不处理推送异常。
            // TODO: 扩展死信接口支持 fire-and-forget 异步操作补偿（如 onPushFailed），
            //       或在此处写入 Redis 补偿标记键供后台 Job 扫描。
        }
    }

    /**
     * 私聊懒加载前置处理（2026-07 改造）。
     *
     * 触发条件：客户端传了 `req.targetUid != 0`。
     * 不论 `req.conversationId` 是否一并传，都以 `target_uid` 为准触发 createPrivateConversation。
     * 传 `conversation_id` 的语义在 createPrivateConversation 内部是"幂等查/建"——同名会话只会有一份。
     *
     * 群聊场景：本方法不触发懒加载，群聊要求发送方必须是 active 成员。
     * 若客户端对群聊误传 target_uid，`createPrivateConversation` 会因好友校验失败而抛 NOT_FRIEND，
     * 这是预期行为：群聊应忽略 target_uid 字段。
     *
     * 并发安全：`createPrivateConversation` 内部用 `txRunner.execute` 串行化事务，
     * 私聊 convId 由双方 UID 排序生成（`private:<smaller>:<larger>`），双发天然幂等。
     *
     * @param req 发送消息请求
     * @param senderUid 发送方 UID（来自 session）
     * @return Pair(resolvedConvId, didCreate)
     *         - resolvedConvId: 实际使用的会话 ID（懒加载时填入 createPrivateConversation 的返回值）
     *         - didCreate: 是否真正触发了 createPrivateConversation（影响 response.conversation 是否填充）
     */
    private suspend fun ensurePrivateConversationIfNeeded(
        req: SendMessageReq,
        senderUid: Long
    ): Pair<String?, Boolean> {
        val targetUid = req.targetUid
        if (targetUid == 0L) {
            // 未传 target_uid：不懒加载，走 conversation_id 原路径
            return Pair(null, false)
        }
        // 传了 target_uid：执行 get-or-create 私聊会话（幂等：已存在则恢复 member）
        val convId = conversationService.createPrivateConversation(senderUid, targetUid)
        return Pair(convId, true)
    }
}
