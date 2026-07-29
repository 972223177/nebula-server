package com.nebula.service.user

import com.nebula.chat.user.FriendApprovalMode
import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository

/**
 * 用户隐私设置业务契约（2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 所有公开方法声明在此接口；[UserPrivacyServiceImpl] 实现之，聚合 Facade [UserPrivacyService] 经 `by` 委托暴露。
 */
interface UserPrivacyOperations {
    /** 设置在线状态可见性 */
    suspend fun setHideOnlineStatus(userId: Long, req: SetPrivacyReq)

    /** 设置好友申请通过模式 */
    suspend fun setFriendApprovalMode(userId: Long, mode: FriendApprovalMode)

    /** 查询隐私设置（在线状态可见性 + 好友申请通过模式） */
    suspend fun getPrivacySettings(userId: Long): GetPrivacyResp

    /** 查询在线状态可见性（已废弃，推荐使用 getPrivacySettings） */
    @Deprecated("Use getPrivacySettings instead", ReplaceWith("getPrivacySettings(userId)"))
    suspend fun getHideOnlineStatus(userId: Long, req: GetPrivacyReq): GetPrivacyResp

    /** 获取用户好友申请通过模式 */
    suspend fun getFriendApprovalMode(userId: Long): FriendApprovalMode

    /** 批量查询在线状态隐藏用户 */
    suspend fun batchGetHideOnlineStatus(uids: List<Long>): Set<Long>
}

/**
 * 用户隐私设置业务服务实现（D-09, D-11, D-57）。
 *
 * 提供在线状态可见性和好友申请通过模式的设置和查询。
 * Redis + MySQL 双写策略：Redis 实时生效，MySQL 异步持久化。
 */
class UserPrivacyServiceImpl(
    private val privacyRepository: PrivacyRepository,
    private val onlineStatusRepository: OnlineStatusRepository
) : UserPrivacyOperations {

    /**
     * 设置在线状态可见性。
     *
     * 更新 Redis 隐私标记后同步调整在线状态 Redis Key，
     * 隐藏时标记为 hidden，恢复时标记为 online。
     *
     * @param userId 当前用户 ID
     * @param req 设置请求（含 hideOnlineStatus）
     */
    override suspend fun setHideOnlineStatus(userId: Long, req: SetPrivacyReq) {
        val hide = req.hideOnlineStatus
        privacyRepository.setHideOnlineStatus(userId, hide)

        if (hide) {
            // 隐藏在线状态时，同步标记 Redis 中的在线状态为隐藏
            onlineStatusRepository.setHidden(userId)
        } else {
            // 恢复可见时，标记为在线
            onlineStatusRepository.setOnline(userId)
        }
    }

    /**
     * 设置好友申请通过模式。
     *
     * 委托 PrivacyRepository 执行 Redis 写 + MySQL 异步持久化。
     *
     * @param userId 当前用户 ID
     * @param mode 好友申请通过模式枚举值
     */
    override suspend fun setFriendApprovalMode(userId: Long, mode: FriendApprovalMode) {
        privacyRepository.setFriendApprovalMode(userId, mode.number)
    }

    /**
     * 查询隐私设置（在线状态可见性 + 好友申请通过模式）。
     *
     * @param userId 当前用户 ID
     * @return 隐私设置响应
     */
    override suspend fun getPrivacySettings(userId: Long): GetPrivacyResp {
        val hide = privacyRepository.getHideOnlineStatus(userId)
        val approvalMode = privacyRepository.getFriendApprovalMode(userId)
        return buildGetPrivacyResp(hide, approvalMode)
    }

    /**
     * 查询在线状态可见性（已废弃，推荐使用 getPrivacySettings）。
     */
    @Deprecated("Use getPrivacySettings instead", ReplaceWith("getPrivacySettings(userId)"))
    override suspend fun getHideOnlineStatus(userId: Long, req: GetPrivacyReq): GetPrivacyResp {
        return getPrivacySettings(userId)
    }

    /**
     * 获取用户好友申请通过模式。
     *
     * @param userId 用户 ID
     * @return 好友申请通过模式枚举值
     */
    override suspend fun getFriendApprovalMode(userId: Long): FriendApprovalMode {
        val mode = privacyRepository.getFriendApprovalMode(userId)
        return FriendApprovalMode.forNumber(mode)
    }

    /**
     * 批量查询在线状态隐藏用户。
     *
     * @param uids 待查询的用户 ID 列表
     * @return 隐藏了在线状态的用户 ID 集合
     */
    override suspend fun batchGetHideOnlineStatus(uids: List<Long>): Set<Long> {
        return privacyRepository.batchGetHideOnlineStatus(uids)
    }
}
