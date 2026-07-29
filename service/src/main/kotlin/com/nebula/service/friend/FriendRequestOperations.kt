package com.nebula.service.friend

import com.nebula.chat.friend.*

/**
 * 好友申请子域接口（D-51, D-52, D-54）。
 *
 * 覆盖好友申请的创建、接受、拒绝与申请列表查询等「申请生命周期」操作。
 * 由 [FriendRequestService] 实现，[FriendService] 经 Kotlin 类委托（`by`）聚合，Handler 零改动。
 */
interface FriendRequestOperations {

    /**
     * 发送好友申请（D-51, D-52, D-54）。
     *
     * 校验 A≠B → 检查已有好友 → 双向竞赛检测 → 检查重复申请 → 创建申请。
     *
     * @param req 好友申请请求
     * @param fromUid 发起者 UID
     * @return 申请结果（含 requestId），以及是否触发了双向竞赛
     */
    suspend fun addFriend(req: FriendAddReq, fromUid: Long): FriendAddResult

    /**
     * 接受好友申请。
     *
     * @param req 接受请求
     * @param userId 当前用户 ID（被申请人）
     * @return 接受结果
     */
    suspend fun acceptFriendRequest(req: FriendAcceptReq, userId: Long): FriendAcceptResult

    /**
     * 拒绝好友申请。
     *
     * @param req 拒绝请求
     * @param userId 当前用户 ID
     */
    suspend fun rejectFriendRequest(req: FriendRejectReq, userId: Long)

    /**
     * 查询好友申请列表。
     *
     * @param req 申请列表请求
     * @param userId 当前用户 ID
     * @return 申请列表响应
     */
    suspend fun getFriendRequests(req: FriendRequestsReq, userId: Long): FriendRequestsResp
}
