package com.nebula.service.friend

import com.nebula.chat.friend.*

/**
 * 好友关系与关系查询子域接口（D-51, D-54）。
 *
 * 覆盖好友关系的删除、列表，以及好友关系/双向关系的查询（含内部使用的全量查询）。
 * 由 [FriendshipService] 实现，[FriendService] 经 Kotlin 类委托（`by`）聚合，Handler 零改动。
 */
interface FriendshipOperations {

    /**
     * 删除好友（软删除）。
     *
     * @param req 删除请求
     * @param userId 当前用户 ID
     */
    suspend fun deleteFriend(req: FriendDeleteReq, userId: Long)

    /**
     * 查询好友列表。
     *
     * @param req 列表请求
     * @param userId 当前用户 ID
     * @return 好友列表响应
     */
    suspend fun listFriends(req: FriendListReq, userId: Long): FriendListResp

    /**
     * 查询用户的所有好友关系（不分页，仅限内部使用）。
     *
     * 返回 [FriendshipInfo] 替代在 gateway 层直接暴露 JPA 实体，
     * 仅包含 gateway 层需要的 userId、friendId 和 deleted 字段。
     *
     * @param userId 用户 ID
     * @return 好友关系信息 DTO 列表
     */
    suspend fun findFriendsByUserId(userId: Long): List<FriendshipInfo>

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
    suspend fun findFriendshipBetween(userId1: Long, userId2: Long): FriendshipInfo?

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
    suspend fun checkRelation(currentUserId: Long, targetUid: Long): FriendRelationResult

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
    ): List<FriendRelationResult>
}
