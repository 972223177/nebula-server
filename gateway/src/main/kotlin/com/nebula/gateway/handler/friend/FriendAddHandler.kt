package com.nebula.gateway.handler.friend

import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAcceptedPayload
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendRequestPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationConstants.CONV_TYPE_PRIVATE
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
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
 * 发送好友申请 Handler（D-51, D-52, D-54）。
 *
 * 校验 A≠B（D-54）→ 检查已有好友（D-51）→ 双向竞赛检测（D-52）
 * → 检查重复申请 → 创建 FriendRequestEntity → 推送 FRIEND_REQUEST。
 *
 * 双向竞赛检测：若 B 已向 A 发送 pending 申请，则自动创建好友关系 + 私聊会话 + 推送 FRIEND_ACCEPTED。
 */
class FriendAddHandler(
    private val friendRequestRepository: FriendRequestRepository,
    private val friendshipRepository: FriendshipRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val lockManager: ConversationLockManager,
    private val transactionTemplate: TransactionTemplate,
    private val pushService: PushService
) : Handler<FriendAddReq, FriendAddResp> {

    override val method: String = "friend/add"

    override suspend fun handle(req: FriendAddReq): FriendAddResp {
        val session = currentCoroutineContext().requireSession()
        val fromUid = session.userId
        val toUid = req.toUid

        // D-54: 不能添加自己为好友
        if (fromUid == toUid) {
            throw FriendException(BizCode.SELF_FRIEND)
        }

        // 排序 uid
        val smaller = minOf(fromUid, toUid)
        val larger = maxOf(fromUid, toUid)

        // D-51: 检查是否已是好友
        val existingFriendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        if (existingFriendship != null && existingFriendship.deleted == 0) {
            throw FriendException(BizCode.ALREADY_FRIEND)
        }

        // D-52: 双向竞赛检测 — 对方是否已向我发送 pending 申请
        val reverseRequest = withContext(Dispatchers.IO) {
            friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0)
        }
        if (reverseRequest != null) {
            // 双向竞赛：自动好友 + 创建私聊会话 + 推送 FRIEND_ACCEPTED
            val convId = buildPrivateConvId(smaller, larger)

            lockManager.withLock(convId) {
                transactionTemplate.execute {
                    // 更新对方申请为 accepted
                    reverseRequest.status = 1
                    friendRequestRepository.save(reverseRequest)

                    // 创建/恢复好友关系（D-45）
                    val friendship = existingFriendship ?: FriendshipEntity(
                        userId = smaller,
                        friendId = larger
                    )
                    if (friendship.deleted == 1) {
                        // D-45: 恢复已删除的好友关系
                        friendship.deleted = 0
                    }
                    friendshipRepository.save(friendship)

                    // 创建私聊会话（D-43）
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
                .setUid(fromUid)
                .setConversationId(convId)
                .build()
            pushService.pushEventToUser(fromUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())
            pushService.pushEventToUser(toUid, PushEventType.FRIEND_ACCEPTED, acceptedPayload.toByteString())

            return FriendAddResp.newBuilder()
                .setRequestId(reverseRequest.id ?: 0L)
                .build()
        }

        // D-51: 检查是否已有待处理申请（同方向重复申请）
        val existingRequest = withContext(Dispatchers.IO) {
            friendRequestRepository.findByFromUidAndToUidAndStatus(fromUid, toUid, 0)
        }
        if (existingRequest != null) {
            throw FriendException(BizCode.REQUEST_HANDLED, "已存在待处理的好友申请")
        }

        // 创建好友申请
        val requestEntity = FriendRequestEntity(
            fromUid = fromUid,
            toUid = toUid,
            status = 0,
            message = req.message
        )
        val savedRequest = withContext(Dispatchers.IO) {
            friendRequestRepository.save(requestEntity)
        }

        // 推送 FRIEND_REQUEST 给目标用户
        val requestPayload = FriendRequestPayload.newBuilder()
            .setRequestId(savedRequest.id ?: 0L)
            .setFromUid(fromUid)
            .setFromUsername("")  // 客户端通过 fromUid 自行查询用户信息
            .setFromAvatar("")
            .setMessage(req.message)
            .build()
        pushService.pushEventToUser(toUid, PushEventType.FRIEND_REQUEST, requestPayload.toByteString())

        return FriendAddResp.newBuilder()
            .setRequestId(savedRequest.id ?: 0L)
            .build()
    }

    companion object {
        /**
         * 构造私聊会话 ID，格式 `private:smaller:larger`（D-43）。
         * smaller/larger 确保双方一致。
         */
        fun buildPrivateConvId(smaller: Long, larger: Long): String {
            return "private:$smaller:$larger"
        }
    }
}
