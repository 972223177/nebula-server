package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendRequestPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.exception.FriendException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.currentCoroutineContext

/**
 * 发送好友申请 Handler（D-51, D-52, D-54）。
 *
 * 职责：
 * - 委托 FriendService 处理业务逻辑（Service 内部已通过 JpaTxRunner 包裹事务，D-79）
 * - ConstraintViolationException 幂等 catch 处理双向竞赛（D-80）
 * - 推送 FRIEND_REQUEST / FRIEND_ACCEPTED
 *
 * @param friendService 好友业务服务
 * @param pushService 推送服务
 * @param lockManager 会话级互斥锁管理器（保留以维持 API 兼容，Friend 流程不依赖会话级锁）
 */
class FriendAddHandler(
    private val sensitiveWordService: SensitiveWordService,
    private val friendService: FriendService,
    private val pushService: PushService,
    @Suppress("unused") private val lockManager: ConversationLockManager
) : Handler<FriendAddReq, FriendAddResp> {

    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        val fromUid = session.userId

        // 敏感词检测：好友申请附言含敏感词则拒绝发送，直接报接口错误（CONTENT_VIOLATION）。
        // 验证语是公开文本，采用拒绝策略（与 chat/send 的脱敏策略不同）。
        if (req.message.isNotBlank() && sensitiveWordService.contains(req.message)) {
            throw BizException(BizCode.CONTENT_VIOLATION, "好友申请附言包含敏感内容")
        }

        // Service 内部事务（D-79 + D-80）
        // PersistenceException 幂等 catch 处理双向竞赛
        // ⚠️ JpaTxRunner 透传原始异常，非 PersistenceException 的 JPA 异常会漏穿到
        // ExceptionInterceptor 并返回 9000。在此兜底捕获以暴露完整堆栈。
        val result = try {
            friendService.addFriend(req, fromUid)
        } catch (e: PersistenceException) {
            val isConstraintViolation = e.message?.contains("Duplicate", ignoreCase = true) == true ||
                e.message?.contains("ConstraintViolation", ignoreCase = true) == true
            if (isConstraintViolation) {
                // D-80: UK 冲突表示好友关系已存在（双向竞赛中的并行请求被 DB 唯一约束拦截）
                logger.warn(e) { "好友关系已存在，幂等返回: fromUid=$fromUid, toUid=${req.toUid}" }
                val smaller = minOf(fromUid, req.toUid)
                val larger = maxOf(fromUid, req.toUid)
                val existingFriendship = friendService.findFriendshipBetween(smaller, larger)
                if (existingFriendship != null && existingFriendship.deleted == 0) {
                    // 已存在未删除的好友关系 → 直接返回 ALREADY_FRIEND
                    throw FriendException(BizCode.ALREADY_FRIEND)
                }
                // 防御性编程：UK 冲突意味着 DB 中已存在记录，若走到此处说明出现异常状态
                // （如 existingFriendship==null 或 deleted!=0），按冲突处理返回 ALREADY_FRIEND
                throw FriendException(BizCode.ALREADY_FRIEND)
            }
            throw e
        } catch (e: FriendException) {
            throw e  // 已正确映射的异常直接透传
        } catch (e: Exception) {
            // 兜底：记录完整堆栈，转换为 internal error 以便排查
            logger.error(e) { "[friend/add] 未预期异常: fromUid=$fromUid, toUid=${req.toUid}" }
            throw e
        }

        if (result.isMutualAccept || result.isAutoAccepted) {
            // 双向竞赛或自动通过：推送 FRIEND_ACCEPTED 给双方
            val acceptedPayload = FriendAcceptedPayload.newBuilder()
                .setUid(result.toUid)
                .setConversationId(result.convId ?: "")
                .build()
            pushService.pushEventToUser(fromUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
            pushService.pushEventToUser(result.toUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
        } else if (!result.isAutoRejected) {
            // 普通申请（非自动拒绝）：推送 FRIEND_REQUEST 给目标用户
            val requestPayload = FriendRequestPayload.newBuilder()
                .setRequestId(result.requestId)
                .setFromUid(fromUid)
                .setFromUsername("")
                .setFromAvatar("")
                .setMessage(req.message)
                .build()
            pushService.pushEventToUser(result.toUid, PushEventType.FRIEND_REQUEST, requestPayload.toByteString())
        }

        return FriendAddResp.newBuilder()
            .setRequestId(result.requestId)
            .build()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
