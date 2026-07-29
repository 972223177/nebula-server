package com.nebula.service.friend

import com.nebula.chat.friend.*
import com.nebula.chat.user.FriendApprovalMode
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.common.util.toEpochMillis
import com.nebula.repository.dao.*
import com.nebula.repository.entity.*
import com.nebula.service.user.UserPrivacyService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManager
import java.time.LocalDateTime

/**
 * 好友申请子域服务（D-51, D-52, D-54）。
 *
 * 实现 [FriendRequestOperations]，负责好友申请的创建、接受、拒绝与申请列表查询。
 * 私聊会话的创建（[ensureFriendshipAndConversation]）随申请生命周期一并完成。
 *
 * 不依赖网关层组件（PushService、ConversationLockManager 等），
 * 并发控制和推送由调用方（Handler）负责。
 *
 * 事务通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 */
class FriendRequestService(
    private val friendRequestDao: FriendRequestDao,
    private val friendshipDao: FriendshipDao,
    private val conversationDao: ConversationDao,
    private val conversationMemberDao: ConversationMemberDao,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner,
    private val userPrivacyService: UserPrivacyService
) : FriendRequestOperations {

    companion object {
        /** 私聊会话类型常量（CQ-12: 1=私聊，与 SQL DDL 一致） */
        private const val CONV_TYPE_PRIVATE = 1

        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
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
    override suspend fun addFriend(req: FriendAddReq, fromUid: Long): FriendAddResult {
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
    override suspend fun acceptFriendRequest(req: FriendAcceptReq, userId: Long): FriendAcceptResult {
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
    override suspend fun rejectFriendRequest(req: FriendRejectReq, userId: Long) {
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
     * 查询好友申请列表。
     *
     * @param req 申请列表请求
     * @param userId 当前用户 ID
     * @return 申请列表响应
     */
    override suspend fun getFriendRequests(req: FriendRequestsReq, userId: Long): FriendRequestsResp {
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
