package com.nebula.gateway.handler.chat.send
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import java.util.Base64

/**
 * chat/send Handler（D-04, D-05, D-06, D-09, D-11, D-13, D-72）。
 *
 * 职责：
 * - 委托 MessageService 处理核心业务逻辑（参数校验、成员验证、好友检查、去重、写入 Redis Stream）
 * - 写入后发布 fan-out 事件到 fanout:stream，由 [com.nebula.gateway.fanout.FanoutWorker] 后台消费执行未读计数递增和推送
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageService.checkAndSetDedup() 中，handler 层不再处理去重。
 *
 * 2026-07-30（§七 Durable Outbox）：原 fire-and-forget（serverScope.launch + asyncUnreadAndPush）
 * 不可重放（server 中途崩 / 重启丢未读与推送）。改为入队持久事件，由 FanoutWorker 消费并 XACK，
 * 重启可重放（幂等锁防未读双计）。入队 fail-open：Redis 不可用时宁可暂不推送，也不让发送失败。
 *
 * @param sensitiveWordService 敏感词服务（发送前内容脱敏，含敏感词则替换为 * 后照常发送）
 * @param messageService 消息业务服务（去重 + 写入 + 未读计数）
 * @param conversationService 会话业务服务（成员查询，供懒加载）
 * @param fanoutQueue fan-out 事件队列（持久化未读 / 推送意图）
 */
class SendMessageHandler(
    private val sensitiveWordService: SensitiveWordService,
    private val messageService: MessageService,
    private val conversationService: ConversationService,
    private val fanoutQueue: RedisStreamQueue
) : Handler<SendMessageReq, SendMessageResp> {

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

            // Step 3: 未读计数 + 推送 — 发布持久化 fan-out 事件（§七 Durable Outbox）。
            // 事件入 fanout:stream 后由 FanoutWorker 后台消费，server 重启 / 处理中途崩溃可重放。
            // fail-open：Redis 不可用时宁可暂不推送（消息已持久化，client 拉历史可补偿），不让发送失败。
            try {
                fanoutQueue.enqueue(buildFanoutEvent(result))
            } catch (e: Exception) {
                logger.error(e) { "fanout 事件入队失败，跳过（消息已持久化）: msgId=${result.msgId}" }
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
     * 构造 fan-out 事件 payload（§七 Durable Outbox）。
     *
     * 将「待推送 / 待加未读」意图持久化到 fanout:stream，由 [com.nebula.gateway.fanout.FanoutWorker] 后台消费。
     * chatMessage 以 proto 二进制 Base64 编码入队，消费端解析还原。
     *
     * @param result 消息发送结果
     * @return fan-out 事件字段表
     */
    private fun buildFanoutEvent(result: SendMessageResult): Map<String, String> = buildMap {
        put("conversationId", result.conversationId)
        put("msgId", result.msgId.toString())
        put("senderUid", result.senderUid.toString())
        put("chatMessage", Base64.getEncoder().encodeToString(result.chatMessage.toByteArray()))
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
