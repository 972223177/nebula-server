package com.nebula.service.friend

import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendDeleteReq
import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.chat.friend.FriendRequestItem
import com.nebula.chat.friend.FriendBrief
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 好友业务服务（D-51, D-52, D-54）。
 *
 * 提供好友申请、接受、拒绝、删除、列表等业务逻辑。
 * 不依赖网关层组件（PushService、ConversationLockManager 等），
 * 并发控制和推送由调用方（Handler）负责。
 */
class FriendService(
    private val friendRequestRepository: FriendRequestRepository,
    private val friendshipRepository: FriendshipRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val userRepository: UserRepository,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) {

    companion object {
        /** 私聊会话类型常量 */
        private const val CONV_TYPE_PRIVATE = 0

        /**
         * 构造私聊会话 ID，格式 `private:smaller:larger`（D-43）。
         */
        fun buildPrivateConvId(smaller: Long, larger: Long): String {
            return "private:$smaller:$larger"
        }
    }

    /**
     * 发送好友申请（D-51, D-52, D-54）。
     *
     * 校验 A≠B → 检查已有好友 → 双向竞赛检测 → 检查重复申请 → 创建申请。
     *
     * @param req 好友申请请求
     * @param fromUid 发起者 UID
     * @return 申请结果（含 requestId），以及是否触发了双向竞赛
     */
    suspend fun addFriend(req: FriendAddReq, fromUid: Long): FriendAddResult {
        val toUid = req.toUid

        if (fromUid == toUid) {
            throw FriendException(BizCode.SELF_FRIEND)
        }

        val smaller = minOf(fromUid, toUid)
        val larger = maxOf(fromUid, toUid)

        // 检查是否已是好友
        val existingFriendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        if (existingFriendship != null && existingFriendship.deleted == 0) {
            throw FriendException(BizCode.ALREADY_FRIEND)
        }

        // 双向竞赛检测：对方是否已发送 pending 申请
        val reverseRequest = withContext(Dispatchers.IO) {
            friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0)
        }
        if (reverseRequest != null) {
            // 双向竞赛：自动创建好友关系 + 私聊会话
            val convId = buildPrivateConvId(smaller, larger)

            // 更新对方申请为 accepted
            reverseRequest.status = 1
            // D-80/H15: saveAndFlush 立即触发 UK 检查，在事务内检测重复而非提交时
            withContext(Dispatchers.IO) { friendRequestRepository.saveAndFlush(reverseRequest) }

            // 创建/恢复好友关系
            val friendship = existingFriendship ?: FriendshipEntity(
                userId = smaller,
                friendId = larger
            )
            if (friendship.deleted == 1) {
                friendship.deleted = 0
            }
            // D-80/H15: saveAndFlush 立即触发 UK 检查，在事务内检测重复
            withContext(Dispatchers.IO) { friendshipRepository.saveAndFlush(friendship) }

            // 创建私聊会话（如果不存在）
            var conv = withContext(Dispatchers.IO) {
                conversationRepository.findById(convId).orElse(null)
            }
            if (conv == null) {
                conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
                conv.id = convId
                conv.createdAt = LocalDateTime.now()
                conv.updatedAt = LocalDateTime.now()
                withContext(Dispatchers.IO) { conversationRepository.save(conv) }
            }

            // 创建双方会话成员
            listOf(smaller, larger).forEach { uid ->
                val existingMember = withContext(Dispatchers.IO) {
                    conversationMemberRepository.findByConversationIdAndUserId(convId, uid)
                }
                if (existingMember == null) {
                    val member = ConversationMemberEntity(
                        conversationId = convId,
                        userId = uid
                    )
                    member.joinedAt = LocalDateTime.now()
                    withContext(Dispatchers.IO) { conversationMemberRepository.save(member) }
                }
            }

            return FriendAddResult(
                requestId = reverseRequest.id ?: 0L,
                isMutualAccept = true,
                convId = convId,
                fromUid = fromUid,
                toUid = toUid
            )
        }

        // 检查是否已有待处理申请
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

        return FriendAddResult(
            requestId = savedRequest.id ?: 0L,
            isMutualAccept = false,
            convId = null,
            fromUid = fromUid,
            toUid = toUid
        )
    }

    /**
     * 接受好友申请。
     *
     * @param req 接受请求
     * @param userId 当前用户 ID（被申请人）
     * @return 接受结果
     */
    suspend fun acceptFriendRequest(req: FriendAcceptReq, userId: Long): FriendAcceptResult {
        val requestId = req.requestId
        val request = withContext(Dispatchers.IO) {
            friendRequestRepository.findById(requestId).orElse(null)
        } ?: throw FriendException(BizCode.REQUEST_NOT_FOUND)

        if (request.status != 0) {
            throw FriendException(BizCode.REQUEST_HANDLED)
        }

        if (request.toUid != userId) {
            throw FriendException(BizCode.FORBIDDEN, "无权处理此申请")
        }

        val smaller = minOf(request.fromUid, request.toUid)
        val larger = maxOf(request.fromUid, request.toUid)
        val convId = buildPrivateConvId(smaller, larger)

        // 更新申请状态
        request.status = 1
        withContext(Dispatchers.IO) { friendRequestRepository.save(request) }

        // 创建好友关系
        var friendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        if (friendship == null) {
            friendship = FriendshipEntity(userId = smaller, friendId = larger)
        }
        if (friendship.deleted == 1) {
            friendship.deleted = 0
        }
        withContext(Dispatchers.IO) { friendshipRepository.save(friendship) }

        // 创建私聊会话
        var conv = withContext(Dispatchers.IO) {
            conversationRepository.findById(convId).orElse(null)
        }
        if (conv == null) {
            conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
            conv.id = convId
            conv.createdAt = LocalDateTime.now()
            conv.updatedAt = LocalDateTime.now()
            withContext(Dispatchers.IO) { conversationRepository.save(conv) }
        }

        // 创建双方会话成员
        listOf(smaller, larger).forEach { uid ->
            val existingMember = withContext(Dispatchers.IO) {
                conversationMemberRepository.findByConversationIdAndUserId(convId, uid)
            }
            if (existingMember == null) {
                val member = ConversationMemberEntity(
                    conversationId = convId,
                    userId = uid
                )
                member.joinedAt = LocalDateTime.now()
                withContext(Dispatchers.IO) { conversationMemberRepository.save(member) }
            }
        }

        return FriendAcceptResult(
            fromUid = request.fromUid,
            toUid = request.toUid,
            convId = convId
        )
    }

    /**
     * 拒绝好友申请。
     *
     * @param req 拒绝请求
     * @param userId 当前用户 ID
     */
    suspend fun rejectFriendRequest(req: FriendRejectReq, userId: Long) {
        val requestId = req.requestId
        val request = withContext(Dispatchers.IO) {
            friendRequestRepository.findById(requestId).orElse(null)
        } ?: throw FriendException(BizCode.REQUEST_NOT_FOUND)

        if (request.status != 0) {
            throw FriendException(BizCode.REQUEST_HANDLED)
        }

        if (request.toUid != userId) {
            throw FriendException(BizCode.FORBIDDEN, "无权处理此申请")
        }

        request.status = 2 // 拒绝
        withContext(Dispatchers.IO) { friendRequestRepository.save(request) }
    }

    /**
     * 删除好友（软删除）。
     *
     * @param req 删除请求
     * @param userId 当前用户 ID
     */
    suspend fun deleteFriend(req: FriendDeleteReq, userId: Long) {
        val targetUid = req.uid
        val smaller = minOf(userId, targetUid)
        val larger = maxOf(userId, targetUid)

        val friendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }
        if (friendship == null || friendship.deleted == 1) {
            throw FriendException(BizCode.FRIEND_NOT_FOUND)
        }

        friendship.deleted = 1
        withContext(Dispatchers.IO) { friendshipRepository.save(friendship) }
    }

    /**
     * 查询好友列表。
     *
     * @param req 列表请求
     * @param userId 当前用户 ID
     * @return 好友列表响应
     */
    suspend fun listFriends(req: FriendListReq, userId: Long): FriendListResp {
        val cursor = req.cursor
        val limit = req.limit.coerceIn(1, 100)

        val friendships = withContext(Dispatchers.IO) {
            friendshipRepository.findFriendsByUserId(
                userId = userId,
                cursor = cursor,
                pageable = org.springframework.data.domain.PageRequest.of(0, limit + 1)
            )
        }

        val hasMore = friendships.size > limit
        val result = if (hasMore) friendships.dropLast(1) else friendships

        val friendUids = result.map { f ->
            if (f.userId == userId) f.friendId else f.userId
        }

        // 批量查询用户信息和在线状态
        val userMap = if (friendUids.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                userRepository.findAllById(friendUids.map { it })
            }.associateBy { it.id }
        } else {
            emptyMap()
        }

        val statusMap = if (friendUids.isNotEmpty()) {
            onlineStatusRepository.batchGetStatus(friendUids)
        } else {
            emptyMap()
        }

        val hiddenUids = if (friendUids.isNotEmpty()) {
            privacyRepository.batchGetHideOnlineStatus(friendUids)
        } else {
            emptySet()
        }

        val builder = FriendListResp.newBuilder()
        result.forEachIndexed { index, _ ->
            val uid = friendUids.getOrNull(index) ?: return@forEachIndexed
            val user = userMap[uid]
            val statusData = statusMap[uid]
            val isOnline = statusData != null
            val isHidden = uid in hiddenUids

            builder.addFriends(FriendBrief.newBuilder()
                .setUid(uid)
                .setUsername(user?.username ?: "")
                .setDisplayName(user?.nickname ?: "")
                .setAvatarUrl(user?.avatar ?: "")
                .setStatus(if (isOnline && !isHidden) 1 else 0)
                .build())
        }
        return builder.build()
    }

    /**
     * 查询好友申请列表。
     *
     * @param req 申请列表请求
     * @param userId 当前用户 ID
     * @return 申请列表响应
     */
    suspend fun getFriendRequests(req: FriendRequestsReq, userId: Long): FriendRequestsResp {
        val requests = withContext(Dispatchers.IO) {
            friendRequestRepository.findByToUidAndStatus(userId, 0)
        }

        val fromUids = requests.map { it.fromUid }.distinct()
        val userMap = if (fromUids.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                userRepository.findAllById(fromUids.map { it })
            }.associateBy { it.id }
        } else {
            emptyMap()
        }

        val builder = FriendRequestsResp.newBuilder()
        requests.forEach { reqEntity ->
            val user = userMap[reqEntity.fromUid]
            builder.addRequests(FriendRequestItem.newBuilder()
                .setRequestId(reqEntity.id ?: 0L)
                .setFromUid(reqEntity.fromUid)
                .setFromUsername(user?.username ?: "")
                .setFromAvatar(user?.avatar ?: "")
                .setMessage(reqEntity.message)
                .setStatus(reqEntity.status.toString())
                .setCreatedAt(reqEntity.createdAt?.atZone(java.time.ZoneOffset.UTC)
                    ?.toInstant()?.toEpochMilli() ?: 0)
                .build())
        }
        return builder.build()
    }
}

/**
 * 好友申请结果。
 */
data class FriendAddResult(
    /** 好友申请 ID */
    val requestId: Long,
    /** 是否双向竞赛自动接受（双方同时申请时触发自动建立好友关系） */
    val isMutualAccept: Boolean,
    /** 私聊会话 ID，双向竞赛或接受后分配 */
    val convId: String?,
    /** 发起者用户 ID */
    val fromUid: Long,
    /** 接收者用户 ID */
    val toUid: Long
)

/**
 * 好友接受结果。
 */
data class FriendAcceptResult(
    /** 发起者用户 ID */
    val fromUid: Long,
    /** 接收者用户 ID */
    val toUid: Long,
    /** 私聊会话 ID */
    val convId: String
)
