package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationConstants.CONV_TYPE_PRIVATE
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * 接受好友申请 Handler（D-43, D-45, D-52）。
 *
 * 加载 FriendRequestEntity → 校验 status=pending + toUid=session.userId
 * → 防御性检查是否已是好友 → 单事务（更新 request.status=1
 * → 创建/恢复 FriendshipEntity D-45 → 创建私聊会话 type=1 D-43
 * → 创建 2 个 ConversationMemberEntity）→ 事务后推送 FRIEND_ACCEPTED 给双方。
 */
class FriendAcceptHandler(
    private val friendRequestRepository: FriendRequestRepository,
    private val friendshipRepository: FriendshipRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<FriendAcceptReq, Response> {

    override val method: String = "friend/accept"

    override suspend fun handle(req: FriendAcceptReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 加载好友申请
        val request = withContext(Dispatchers.IO) {
            friendRequestRepository.findById(req.requestId)
                .orElseThrow { FriendException(BizCode.REQUEST_NOT_FOUND) }
        }

        // 校验接收方是否为当前用户
        if (request.toUid != session.userId) {
            throw FriendException(BizCode.FORBIDDEN, "只能处理自己的好友申请")
        }

        // 校验申请状态
        if (request.status != 0) {
            throw FriendException(BizCode.REQUEST_HANDLED)
        }

        val fromUid = request.fromUid
        val toUid = session.userId
        val smaller = minOf(fromUid, toUid)
        val larger = maxOf(fromUid, toUid)

        // 防御性检查是否已是好友
        val existingFriendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        if (existingFriendship != null && existingFriendship.deleted == 0) {
            throw FriendException(BizCode.ALREADY_FRIEND)
        }

        val convId = FriendAddHandler.buildPrivateConvId(smaller, larger)

        // 单事务：更新申请 + 创建/恢复好友 + 创建私聊会话 + 创建成员
        lockManager.withLock(convId) {
            transactionTemplate.execute {
                // 更新申请状态为 accepted
                request.status = 1
                friendRequestRepository.save(request)

                // D-45: 创建或恢复好友关系
                val friendship = existingFriendship ?: FriendshipEntity(
                    userId = smaller,
                    friendId = larger
                )
                if (friendship.deleted == 1) {
                    // 恢复已删除的好友关系
                    friendship.deleted = 0
                }
                friendshipRepository.save(friendship)

                // D-43: 创建私聊会话（如果不存在）
                var conv = conversationRepository.findById(convId).orElse(null)
                if (conv == null) {
                    conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
                    conv.id = convId
                    conversationRepository.save(conv)
                }

                // 创建双方会话成员（如果不存在）
                listOf(smaller, larger).forEach { uid ->
                    val existingMember = conversationMemberRepository.findByConversationIdAndUserId(convId, uid)
                    if (existingMember == null) {
                        val member = ConversationMemberEntity(
                            conversationId = convId,
                            userId = uid
                        )
                        member.joinedAt = LocalDateTime.now()
                        conversationMemberRepository.save(member)
                    }
                }
            }
        }

        // 事务后推送 FRIEND_ACCEPTED 给双方
        val acceptedPayload = FriendAcceptedPayload.newBuilder()
            .setUid(toUid)
            .setConversationId(convId)
            .build()
        pushService.pushEventToUser(fromUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
        pushService.pushEventToUser(toUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg(BizCode.OK.msg)
            .build()
    }
}
