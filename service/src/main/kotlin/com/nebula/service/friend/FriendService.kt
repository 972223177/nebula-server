package com.nebula.service.friend

import com.nebula.chat.friend.*
import com.nebula.chat.user.FriendApprovalMode
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.common.util.toEpochMillis
import com.nebula.repository.dao.*
import com.nebula.repository.entity.*
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.service.user.UserPrivacyService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManager
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
    private val userPrivacyService: UserPrivacyService
) {

    companion object {
        /** 私聊会话类型常量（CQ-12: 1=私聊，与 SQL DDL 一致） */
        private const val CONV_TYPE_PRIVATE = 1

        /** M6: 批量关系查询上限，防止单事务过大数据集 */
        private const val MAX_BATCH_CHECK_SIZE = 500

        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}

        /**
         * 构造私聊会话 ID，格式 `private:smaller:larger`（D-43）。
         */
        fun buildPrivateConvId(smaller: Long, larger: Long): String {
            return "private:$smaller:$larger"
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 确保好友关系+私聊会话+双方成员存在（D-51）。
     *
     * 三种场景共用此逻辑：
     * 1. 自动通过（FriendApprovalMode.AUTO_ACCEPT）
     * 2. 双向竞赛（mutual accept）
     * 3. 接受申请（acceptFriendRequest）
     *
     * @param em JPA EntityManager
     * @param smaller 较小的用户 ID
     * @param larger 较大的用户 ID
     * @param convId 私聊会话 ID
     * @param existingFriendship 已有的好友关系（可能为 null 或不活跃）
     */
    private fun ensureFriendshipAndConversation(
        em: EntityManager,
        smaller: Long,
        larger: Long,
        convId: String,
        existingFriendship: FriendshipEntity?
    ) {
        // 创建或恢复好友关系
        if (existingFriendship == null) {
            friendshipDao.insert(em, FriendshipEntity(userId = smaller, friendId = larger).apply {
                deleted = 0
                createdAt = LocalDateTime.now()
            })
        } else if (!existingFriendship.isActive) {
            existingFriendship.deleted = 0
        }

        // 创建私聊会话（如果不存在）
        var conv = conversationDao.findById(em, convId)
        if (conv == null) {
            conv = ConversationEntity(type = CONV_TYPE_PRIVATE, name = "").apply {
                id = convId
                createdAt = LocalDateTime.now()
                updatedAt = LocalDateTime.now()
            }
            conversationDao.insert(em, conv)
        }

        // 创建双方会话成员
        listOf(smaller, larger).forEach { uid ->
            val existingMember = conversationMemberDao.findByConversationIdAndUserId(em, convId, uid)
            if (existingMember == null) {
                conversationMemberDao.insert(em, ConversationMemberEntity(
                    conversationId = convId, userId = uid
                ).apply { joinedAt = LocalDateTime.now() })
            }
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

        // 预先查询目标用户的好友申请通过模式（走 UserPrivacyService→Repository，独立于后续事务）
        val approvalMode = userPrivacyService.getFriendApprovalMode(toUid)

        return txRunner.execute { em ->
            // 检查是否已是好友
            val existingFriendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            if (existingFriendship != null && existingFriendship.isActive) {
                throw FriendException(BizCode.ALREADY_FRIEND)
            }

            // 好友申请通过模式检查（使用 Protobuf 枚举，与 UserPrivacyService 返回类型一致）
            when (approvalMode) {
                FriendApprovalMode.AUTO_REJECT -> {
                    // 自动拒绝：直接创建/复用 status=2 的申请记录，不推送通知
                    logger.info { "好友申请自动拒绝: fromUid=$fromUid, toUid=$toUid, mode=AUTO_REJECT" }
                    // 复用既有 (from_uid, to_uid) 记录，避免重复插入触发 uk_from_to_status 冲突（D-80）
                    val autoRejectEntity = friendRequestDao.findByFromUidAndToUid(em, fromUid, toUid)
                    val requestId = if (autoRejectEntity != null) {
                        autoRejectEntity.message = req.message
                        autoRejectEntity.status = 2
                        autoRejectEntity.updatedAt = LocalDateTime.now()
                        autoRejectEntity.id ?: 0L
                    } else {
                        val entity = FriendRequestEntity(
                            fromUid = fromUid,
                            toUid = toUid,
                            status = 2,
                            message = req.message
                        ).apply {
                            createdAt = LocalDateTime.now()
                            updatedAt = LocalDateTime.now()
                        }
                        friendRequestDao.insert(em, entity).id ?: 0L
                    }
                    return@execute FriendAddResult(
                        requestId = requestId,
                        isMutualAccept = false,
                        isAutoAccepted = false,
                        isAutoRejected = true,
                        convId = null,
                        fromUid = fromUid,
                        toUid = toUid
                    )
                }

                FriendApprovalMode.AUTO_ACCEPT -> {
                    // 自动通过：直接建立好友关系 + 私聊会话
                    val convId = buildPrivateConvId(smaller, larger)
                    ensureFriendshipAndConversation(em, smaller, larger, convId, existingFriendship)
                    logger.info { "好友申请自动通过: fromUid=$fromUid, toUid=$toUid, convId=$convId" }
                    return@execute FriendAddResult(
                        requestId = 0L, isMutualAccept = false, isAutoAccepted = true, isAutoRejected = false,
                        convId = convId, fromUid = fromUid, toUid = toUid
                    )
                }

                FriendApprovalMode.WAIT_APPROVAL, FriendApprovalMode.UNRECOGNIZED -> {
                    // 等待同意 / 未知模式 → 走下方原流程（双向竞赛 → 创建申请）
                }
            }

            // WAIT_APPROVAL（默认）：进入原流程 —— 双向竞赛检测
            val reverseRequest = friendRequestDao.findByFromUidAndToUidAndStatus(em, toUid, fromUid, 0)
            if (reverseRequest != null) {
                // 双向竞赛：自动创建好友关系 + 私聊会话
                val convId = buildPrivateConvId(smaller, larger)

                // 对方申请是事务内托管实体，commit 时脏检查自动 flush
                reverseRequest.status = 1

                // 建立好友关系 + 私聊会话 + 双方成员
                ensureFriendshipAndConversation(em, smaller, larger, convId, existingFriendship)

                return@execute FriendAddResult(
                    requestId = reverseRequest.id ?: 0L,
                    isMutualAccept = true,
                    isAutoAccepted = false,
                    isAutoRejected = false,
                    convId = convId,
                    fromUid = fromUid,
                    toUid = toUid
                )
            }

            // 检查是否已有同一 (from_uid, to_uid) 方向的申请
            val existingRequest = friendRequestDao.findByFromUidAndToUid(em, fromUid, toUid)
            if (existingRequest != null) {
                when (existingRequest.status) {
                    // 已存在待处理申请：幂等拒绝
                    0 -> throw FriendException(BizCode.REQUEST_HANDLED, "已存在待处理的好友申请")
                    // 已拒绝/已接受等非 pending 历史记录：复用该记录并重置为待处理，
                    // 避免同一 (from_uid, to_uid) 重复插入不同 status 行而触发
                    // uk_from_to_status 唯一约束冲突（D-80）
                    else -> {
                        existingRequest.message = req.message
                        existingRequest.status = 0
                        existingRequest.updatedAt = LocalDateTime.now()
                        return@execute FriendAddResult(
                            requestId = existingRequest.id ?: 0L,
                            isMutualAccept = false,
                            isAutoAccepted = false,
                            isAutoRejected = false,
                            convId = null,
                            fromUid = fromUid,
                            toUid = toUid
                        )
                    }
                }
            }

            // 创建好友申请
            val requestEntity = FriendRequestEntity(
                fromUid = fromUid,
                toUid = toUid,
                status = 0,
                message = req.message
            ).apply {
                createdAt = LocalDateTime.now()
                updatedAt = LocalDateTime.now()
            }
            val savedRequest = friendRequestDao.insert(em, requestEntity)

            FriendAddResult(
                requestId = savedRequest.id ?: 0L,
                isMutualAccept = false,
                isAutoAccepted = false,
                isAutoRejected = false,
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

            // 防御性自校验：fromUid==toUid 的异常申请（多为历史脏数据）直接拒绝，
            // 避免建立 (uid,uid) 自好友关系与 private:uid:uid 自会话
            if (request.fromUid == request.toUid) {
                throw FriendException(BizCode.SELF_FRIEND)
            }

            if (request.toUid != userId) {
                throw FriendException(BizCode.FORBIDDEN, "无权处理此申请")
            }

            val smaller = minOf(request.fromUid, request.toUid)
            val larger = maxOf(request.fromUid, request.toUid)
            val convId = buildPrivateConvId(smaller, larger)

            // 更新申请状态：commit 时脏检查自动 flush
            request.status = 1

            val existingFriendship = friendshipDao.findByUserIdAndFriendId(em, smaller, larger)
            ensureFriendshipAndConversation(em, smaller, larger, convId, existingFriendship)

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

            // 直接改字段，commit 时脏检查自动 flush（不调用 em.merge / em.update）
            request.status = 2 // 拒绝
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
    suspend fun listFriends(req: FriendListReq, userId: Long): FriendListResp {
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
     * 查询好友申请列表。
     *
     * @param req 申请列表请求
     * @param userId 当前用户 ID
     * @return 申请列表响应
     */
    suspend fun getFriendRequests(req: FriendRequestsReq, userId: Long): FriendRequestsResp {
        val direction = req.direction  // 默认 INCOMING(0)，兼容旧客户端

        val (incomingEntities, outgoingEntities, toUids) = txRunner.execute { em ->
            val incoming = if (direction == FriendRequestDirection.INCOMING || direction == FriendRequestDirection.BOTH) {
                friendRequestDao.findByToUidAndStatus(em, userId, 0)
            } else emptyList()

            val outgoing = if (direction == FriendRequestDirection.OUTGOING || direction == FriendRequestDirection.BOTH) {
                friendRequestDao.findByFromUidAndStatus(em, userId, 0)
            } else emptyList()

            // 收集所有需要查用户信息的 uid（收到的=发送者，发出的=接收者）
            val uids = (incoming.map { it.fromUid } + outgoing.map { it.toUid }).distinct()
            Triple(incoming, outgoing, uids)
        }

        val userMap = if (toUids.isNotEmpty()) {
            txRunner.execute { em -> userDao.findAllById(em, toUids) }
                .associateBy { it.id }
        } else emptyMap()

        val builder = FriendRequestsResp.newBuilder()

        // 收到的申请
        incomingEntities.forEach { reqEntity ->
            val user = userMap[reqEntity.fromUid]
            builder.addRequests(FriendRequestItem.newBuilder()
                .setRequestId(reqEntity.id ?: 0L)
                .setFromUid(reqEntity.fromUid)
                .setFromUsername(user?.username ?: "")
                .setFromAvatar(user?.avatar ?: "")
                .setMessage(reqEntity.message)
                // M22: status 改为 proto enum（FriendRequestStatus），取代原 string 转换
                .setStatus(FriendRequestStatus.forNumber(reqEntity.status))
                .setCreatedAt(reqEntity.createdAt?.toEpochMillis() ?: 0)
                .setDirection(FriendRequestDirection.INCOMING)
                .build())
        }

        // 发出的申请
        outgoingEntities.forEach { reqEntity ->
            val user = userMap[reqEntity.toUid]
            builder.addRequests(FriendRequestItem.newBuilder()
                .setRequestId(reqEntity.id ?: 0L)
                .setFromUid(reqEntity.toUid)      // from_uid = 对方 UID（接收方）
                .setFromUsername(user?.username ?: "")
                .setFromAvatar(user?.avatar ?: "")
                .setMessage(reqEntity.message)
                // M22: status 改为 proto enum（FriendRequestStatus），取代原 string 转换
                .setStatus(FriendRequestStatus.forNumber(reqEntity.status))
                .setCreatedAt(reqEntity.createdAt?.toEpochMillis() ?: 0)
                .setDirection(FriendRequestDirection.OUTGOING)
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

    /**
     * 查询当前用户与目标用户间的关系状态（friend/check 接口）。
     *
     * 查询顺序：好友 → 我发起的待处理申请 → 对方发起的待处理申请 → 被拒绝的申请 → 无关系。
     * 按此优先级返回首个匹配状态，确保不重复查询。
     *
     * M2 修复：返回值从 Pair 改为 FriendRelationResult，与 batchCheckRelation 一致。
     *
     * @param currentUserId 当前用户 UID
     * @param targetUid 目标用户 UID
     * @return 关系状态结果（含 uid、status、requestId）
     */
    suspend fun checkRelation(currentUserId: Long, targetUid: Long): FriendRelationResult {
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
    suspend fun batchCheckRelation(
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
 * 好友申请结果。
 */
data class FriendAddResult(
    /** 好友申请 ID */
    val requestId: Long,
    /** 是否双向竞赛自动接受（双方同时申请时触发自动建立好友关系） */
    val isMutualAccept: Boolean,
    /** 是否自动通过（目标用户设置了自动通过模式） */
    val isAutoAccepted: Boolean,
    /** 是否自动拒绝（目标用户设置了自动拒绝模式） */
    val isAutoRejected: Boolean,
    /** 私聊会话 ID，双向竞赛或自动通过时分配 */
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
