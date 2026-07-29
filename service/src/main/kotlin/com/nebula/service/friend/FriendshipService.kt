package com.nebula.service.friend

import com.nebula.chat.friend.*
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.repository.dao.*
import com.nebula.repository.entity.*
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.service.user.UserPrivacyService
import jakarta.persistence.EntityManager
import java.time.LocalDateTime

/**
 * 好友关系与关系查询子域服务（D-51, D-54）。
 *
 * 实现 [FriendshipOperations]，负责好友关系的删除、列表，以及好友关系/双向关系查询。
 *
 * 不依赖网关层组件（PushService、ConversationLockManager 等），
 * 并发控制和推送由调用方（Handler）负责。
 *
 * 事务通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 */
class FriendshipService(
    private val friendshipDao: FriendshipDao,
    private val friendRequestDao: FriendRequestDao,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val userPrivacyService: UserPrivacyService
) : FriendshipOperations {

    companion object {
        /** M6: 批量关系查询上限，防止单事务过大数据集 */
        private const val MAX_BATCH_CHECK_SIZE = 500
    }

    /**
     * 删除好友（软删除）。
     *
     * @param req 删除请求
     * @param userId 当前用户 ID
     */
    override suspend fun deleteFriend(req: FriendDeleteReq, userId: Long) {
        val targetUid = req.uid
        val smaller = minOf(userId, targetUid)
        val larger = maxOf(userId, targetUid)

        txRunner.execute { em ->
            val friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (friendship == null || !friendship.isActive) {
                throw FriendException(BizCode.FRIEND_NOT_FOUND)
            }

            // 直接改字段，commit 时脏检查自动 flush（不调用 em.merge / em.update）
            friendship.deleted = 1
        }
    }

    /**
     * 查询好友列表。
     *
     * @param req 列表请求
     * @param userId 当前用户 ID
     * @return 好友列表响应
     */
    override suspend fun listFriends(req: FriendListReq, userId: Long): FriendListResp {
        val cursor = req.cursor
        val limit = req.limit.coerceIn(1, 100)

        // 单事务内完成：好友关系 + 批量用户信息查询
        // 避免拆成两个事务导致的"读到事务 1 之后、事务 2 之前的中间状态"问题
        val (result, userMap, hasMore) = txRunner.execute { em ->
            val friendships = friendshipDao.findFriendsByUserId(em, userId, cursor, limit + 1)
            val hasMore = friendships.size > limit
            val page = if (hasMore) friendships.dropLast(1) else friendships
            // 过滤异常的自好友数据(user_id == friend_id), 避免好友列表出现自己账户
            val filtered = page.filter { it.userId != it.friendId }
            val uids = filtered.map { f -> if (f.userId == userId) f.friendId else f.userId }
            val users = if (uids.isNotEmpty()) {
                userDao.findAllById(em, uids).associateBy { it.id }
            } else emptyMap()
            Triple(filtered, users, hasMore)
        }

        val friendUids = result.map { f ->
            if (f.userId == userId) f.friendId else f.userId
        }

        // 状态查询走 Redis
        val statusMap = if (friendUids.isNotEmpty()) {
            onlineStatusRepository.batchGetStatus(friendUids)
        } else emptyMap()

        val hiddenUids = if (friendUids.isNotEmpty()) {
            userPrivacyService.batchGetHideOnlineStatus(friendUids)
        } else emptySet()

        val builder = FriendListResp.newBuilder()
        result.zip(friendUids).forEach { (_, uid) ->
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
     * 查询用户的所有好友关系（不分页，仅限内部使用）。
     *
     * 返回 [FriendshipInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId、friendId 和 deleted 字段。
     *
     * @param userId 用户 ID
     * @return 好友关系信息 DTO 列表
     */
    override suspend fun findFriendsByUserId(userId: Long): List<FriendshipInfo> {
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
    override suspend fun findFriendshipBetween(userId1: Long, userId2: Long): FriendshipInfo? {
        val entity = txRunner.execute { em ->
            friendshipDao.findByUserIdAndFriendId(em, minOf(userId1, userId2), maxOf(userId1, userId2))
        }
        return entity?.let { FriendshipInfo(userId = it.userId, friendId = it.friendId, deleted = it.deleted) }
    }

    /**
     * 查询当前用户与目标用户间的关系状态（friend/check 接口）。
     *
     * 查询顺序：好友 → 我发起的待处理申请 → 对方发起的待处理申请 → 被拒绝的申请 → 无关系。
     * 按此优先级返回首个匹配状态，确保不重复查询。
     *
     * @param currentUserId 当前用户 UID
     * @param targetUid 目标用户 UID
     * @return 关系状态结果（含 uid、status、requestId）
     */
    override suspend fun checkRelation(currentUserId: Long, targetUid: Long): FriendRelationResult {
        val smaller = minOf(currentUserId, targetUid)
        val larger = maxOf(currentUserId, targetUid)

        return txRunner.execute { em ->
            // 1. 检查好友关系
            val friendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (friendship != null && friendship.isActive) {
                return@execute FriendRelationResult(targetUid, FriendRelationStatus.FRIEND, null)
            }

            // 2. 我→对方 pending 申请
            val myRequest = friendRequestDao.findByFromUidAndToUidAndStatus(em, currentUserId, targetUid, 0)
            if (myRequest != null) {
                return@execute FriendRelationResult(targetUid, FriendRelationStatus.PENDING_SENT, myRequest.id)
            }

            // 3. 对方→我 pending 申请
            val theirRequest = friendRequestDao.findByFromUidAndToUidAndStatus(em, targetUid, currentUserId, 0)
            if (theirRequest != null) {
                return@execute FriendRelationResult(targetUid, FriendRelationStatus.PENDING_RECEIVED, theirRequest.id)
            }

            // 4. 检查双方有无被拒绝的申请（rejected = status 2）
            val rejectedRequest = friendRequestDao.findByFromUidAndToUid(em, currentUserId, targetUid)
                ?: friendRequestDao.findByFromUidAndToUid(em, targetUid, currentUserId)
            if (rejectedRequest != null && rejectedRequest.status == 2) {
                return@execute FriendRelationResult(targetUid, FriendRelationStatus.REJECTED, rejectedRequest.id)
            }

            // 5. 无任何关系
            FriendRelationResult(targetUid, FriendRelationStatus.NONE, null)
        }
    }

    /**
     * 批量查询当前用户与多个目标用户的关系状态（friend/batchCheck）。
     *
     * H3 修复：使用批量 DAO 方法替代 N+1 查询模式。
     * 4 条 SQL 覆盖全部关系状态（好友、双向 pending、拒绝），不再逐用户循环查询。
     * 逐用户按优先级返回首个匹配状态。
     *
     * @param currentUserId 当前用户 UID
     * @param targetUids 目标用户 UID 列表
     * @return 关系状态列表（与输入顺序一致）
     */
    override suspend fun batchCheckRelation(
        currentUserId: Long,
        targetUids: List<Long>
    ): List<FriendRelationResult> {
        if (targetUids.isEmpty()) return emptyList()
        // M6: 上限保护，避免单事务内超大数据集
        require(targetUids.size <= MAX_BATCH_CHECK_SIZE) {
            "批量查询上限为 $MAX_BATCH_CHECK_SIZE"
        }

        return txRunner.execute { em ->
            // H3 批量查询：1 条 SQL → 所有好友关系
            val allFriendships = friendshipDao.findAllFriendsByUids(em, currentUserId, targetUids)
            val friendUidSet = allFriendships.map { f ->
                if (f.userId == currentUserId) f.friendId else f.userId
            }.toSet()

            // H3 批量查询：1 条 SQL → 所有双向 pending 申请
            val allPending = friendRequestDao.findAllPendingBidirectional(em, currentUserId, targetUids)
            val sentRequestMap = allPending
                .filter { it.fromUid == currentUserId }
                .associate { it.toUid to it.id!! }
            val receivedRequestMap = allPending
                .filter { it.toUid == currentUserId }
                .associate { it.fromUid to it.id!! }

            // H3 批量查询：1 条 SQL → 所有双向被拒绝申请（status=2）
            val allRejected = friendRequestDao.findAllBidirectional(em, currentUserId, targetUids)
                .filter { it.status == 2 }
            val rejectedMap = allRejected.associate {
                val otherUid = if (it.fromUid == currentUserId) it.toUid else it.fromUid
                otherUid to (it.id ?: 0L)
            }

            // 逐用户按优先级组装结果（O(N) 纯内存操作）
            targetUids.map { uid ->
                when {
                    uid in friendUidSet -> FriendRelationResult(uid, FriendRelationStatus.FRIEND, null)
                    sentRequestMap.containsKey(uid) -> FriendRelationResult(uid, FriendRelationStatus.PENDING_SENT, sentRequestMap[uid])
                    receivedRequestMap.containsKey(uid) -> FriendRelationResult(uid, FriendRelationStatus.PENDING_RECEIVED, receivedRequestMap[uid])
                    rejectedMap.containsKey(uid) -> FriendRelationResult(uid, FriendRelationStatus.REJECTED, rejectedMap[uid])
                    else -> FriendRelationResult(uid, FriendRelationStatus.NONE, null)
                }
            }
        }
    }
}

/**
 * 好友关系查询结果（friend/check / friend/batchCheck 共用）。
 */
data class FriendRelationResult(
    /** 目标用户 UID */
    val uid: Long,
    /** 关系状态 */
    val status: FriendRelationStatus,
    /** 关联的申请 ID（仅 PENDING_SENT/PENDING_RECEIVED/REJECTED 时非 null） */
    val requestId: Long?
)
