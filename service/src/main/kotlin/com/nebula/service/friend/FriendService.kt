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
import com.nebula.repository.dao.ConversationDao
import com.nebula.repository.dao.ConversationMemberDao
import com.nebula.repository.dao.FriendRequestDao
import com.nebula.repository.dao.FriendshipDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.UserDao
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.isActive
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import java.time.LocalDateTime

/**
 * 好友业务服务（D-51, D-52, D-54）。
 *
 * 提供好友申请、接受、拒绝、删除、列表等业务逻辑。
 * 不依赖网关层组件（PushService、ConversationLockManager 等），
 * 并发控制和推送由调用方（Handler）负责。
 *
 * 事务通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 */
class FriendService(
    private val friendRequestDao: FriendRequestDao,
    private val friendshipDao: FriendshipDao,
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) {

    companion object {
        /** 私聊会话类型常量（CQ-12: 1=私聊，与 SQL DDL 一致） */
        private const val CONV_TYPE_PRIVATE = 1

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

        return txRunner.execute { em ->
            // 检查是否已是好友
            val existingFriendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (existingFriendship != null && existingFriendship.isActive) {
                throw FriendException(BizCode.ALREADY_FRIEND)
            }

            // 双向竞赛检测：对方是否已发送 pending 申请
            val reverseRequest = friendRequestDao.findByFromUidAndToUidAndStatus(em, toUid, fromUid, 0)
            if (reverseRequest != null) {
                // 双向竞赛：自动创建好友关系 + 私聊会话
                val convId = buildPrivateConvId(smaller, larger)

                // 更新对方申请为 accepted
                reverseRequest.status = 1
                // D-80/H15: flush 立即触发 UK 检查，在事务内检测重复而非提交时
                em.flush()
                friendRequestDao.update(em, reverseRequest)

                // 创建/恢复好友关系
                val friendship = existingFriendship ?: FriendshipEntity(
                    userId = smaller,
                    friendId = larger
                )
                if (!friendship.isActive) {
                    friendship.deleted = 0
                }
                em.flush()
                friendshipDao.update(em, friendship)

                // 创建私聊会话（如果不存在）
                var conv = conversationDao.findById(em, convId)
                if (conv == null) {
                    conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
                    conv.id = convId
                    conv.createdAt = LocalDateTime.now()
                    conv.updatedAt = LocalDateTime.now()
                    conversationDao.insert(em, conv)
                }

                // 创建双方会话成员
                listOf(smaller, larger).forEach { uid ->
                    val existingMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, uid)
                    if (existingMember == null) {
                        val member = ConversationMemberEntity(
                            conversationId = convId,
                            userId = uid
                        )
                        member.joinedAt = LocalDateTime.now()
                        conversationMemberDao.insert(em, member)
                    }
                }

                return@execute FriendAddResult(
                    requestId = reverseRequest.id ?: 0L,
                    isMutualAccept = true,
                    convId = convId,
                    fromUid = fromUid,
                    toUid = toUid
                )
            }

            // 检查是否已有待处理申请
            val existingRequest = friendRequestDao.findByFromUidAndToUidAndStatus(em, fromUid, toUid, 0)
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
            val savedRequest = friendRequestDao.insert(em, requestEntity)

            FriendAddResult(
                requestId = savedRequest.id ?: 0L,
                isMutualAccept = false,
                convId = null,
                fromUid = fromUid,
                toUid = toUid
            )
        }
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

        return txRunner.execute { em ->
            val request = friendRequestDao.findById(em, requestId)
                ?: throw FriendException(BizCode.REQUEST_NOT_FOUND)

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
            friendRequestDao.update(em, request)

            // 创建好友关系
            var friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (friendship == null) {
                friendship = FriendshipEntity(userId = smaller, friendId = larger)
            }
            if (!friendship.isActive) {
                friendship.deleted = 0
            }
            friendshipDao.update(em, friendship)

            // 创建私聊会话
            var conv = conversationDao.findById(em, convId)
            if (conv == null) {
                conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "")
                conv.id = convId
                conv.createdAt = LocalDateTime.now()
                conv.updatedAt = LocalDateTime.now()
                conversationDao.insert(em, conv)
            }

            // 创建双方会话成员
            listOf(smaller, larger).forEach { uid ->
                val existingMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, uid)
                if (existingMember == null) {
                    val member = ConversationMemberEntity(
                        conversationId = convId,
                        userId = uid
                    )
                    member.joinedAt = LocalDateTime.now()
                    conversationMemberDao.insert(em, member)
                }
            }

            FriendAcceptResult(
                fromUid = request.fromUid,
                toUid = request.toUid,
                convId = convId
            )
        }
    }

    /**
     * 拒绝好友申请。
     *
     * @param req 拒绝请求
     * @param userId 当前用户 ID
     */
    suspend fun rejectFriendRequest(req: FriendRejectReq, userId: Long) {
        val requestId = req.requestId

        txRunner.execute { em ->
            val request = friendRequestDao.findById(em, requestId)
                ?: throw FriendException(BizCode.REQUEST_NOT_FOUND)

            if (request.status != 0) {
                throw FriendException(BizCode.REQUEST_HANDLED)
            }

            if (request.toUid != userId) {
                throw FriendException(BizCode.FORBIDDEN, "无权处理此申请")
            }

            request.status = 2 // 拒绝
            friendRequestDao.update(em, request)
        }
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

        txRunner.execute { em ->
            val friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (friendship == null || !friendship.isActive) {
                throw FriendException(BizCode.FRIEND_NOT_FOUND)
            }

            friendship.deleted = 1
            friendshipDao.update(em, friendship)
        }
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

        val friendships = txRunner.execute { em ->
            friendshipDao.findFriendsByUserId(em, userId, cursor, limit + 1)
        }

        val hasMore = friendships.size > limit
        val result = if (hasMore) friendships.dropLast(1) else friendships

        val friendUids = result.map { f ->
            if (f.userId == userId) f.friendId else f.userId
        }

        // 批量查询用户信息
        val userMap = if (friendUids.isNotEmpty()) {
            txRunner.execute { em -> userDao.findAllById(em, friendUids) }
                .associateBy { it.id }
        } else emptyMap()

        // 状态查询走 Redis
        val statusMap = if (friendUids.isNotEmpty()) {
            onlineStatusRepository.batchGetStatus(friendUids)
        } else emptyMap()

        val hiddenUids = if (friendUids.isNotEmpty()) {
            privacyRepository.batchGetHideOnlineStatus(friendUids)
        } else emptySet()

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

        // D-46: 设置游标分页字段
        if (hasMore) {
            builder.setNextCursor(result.last().id ?: 0)
            builder.setHasMore(true)
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
        val requests = txRunner.execute { em ->
            friendRequestDao.findByToUidAndStatus(em, userId, 0)
        }

        val fromUids = requests.map { it.fromUid }.distinct()
        val userMap = if (fromUids.isNotEmpty()) {
            txRunner.execute { em -> userDao.findAllById(em, fromUids) }
                .associateBy { it.id }
        } else emptyMap()

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

    /**
     * 查询用户的所有好友关系（不分页，仅限内部使用）。
     *
     * 返回 [FriendshipInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId、friendId 和 deleted 字段。
     *
     * @param userId 用户 ID
     * @return 好友关系信息 DTO 列表
     */
    suspend fun findFriendsByUserId(userId: Long): List<FriendshipInfo> {
        val entities = txRunner.execute { em ->
            friendshipDao.findFriendsByUserId(em, userId, 0, Int.MAX_VALUE)
        }
        return entities.map { FriendshipInfo(userId = it.userId, friendId = it.friendId, deleted = it.deleted) }
    }

    /**
     * 查询两个用户之间的好友关系，不存在时返回 null。
     *
     * 返回 [FriendshipInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId、friendId 和 deleted 字段。
     *
     * @param userId1 用户 ID
     * @param userId2 用户 ID
     * @return 好友关系信息 DTO，不存在时返回 null
     */
    suspend fun findFriendshipBetween(userId1: Long, userId2: Long): FriendshipInfo? {
        val entity = txRunner.execute { em ->
            friendshipDao.findByUserIdAndFriendId(em, minOf(userId1, userId2), maxOf(userId1, userId2))
        }
        return entity?.let { FriendshipInfo(userId = it.userId, friendId = it.friendId, deleted = it.deleted) }
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
