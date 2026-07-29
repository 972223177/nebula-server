package com.nebula.service.friend

import com.nebula.repository.dao.*

/**
 * 好友业务聚合服务（D-51, D-52, D-54）。
 *
 * 由 [FriendRequestService]（好友申请子域）与 [FriendshipService]（好友关系/关系查询子域）
 * 经 Kotlin 类委托（`by`）聚合，编译器自动生成转发，零手写样板。
 * 不依赖网关层组件（PushService、ConversationLockManager 等），并发控制和推送由调用方（Handler）负责。
 *
 * Handler 仍只依赖 `FriendService` 类型，零改动。
 */
class FriendService(
    friendRequestService: FriendRequestService,
    friendshipService: FriendshipService
) : FriendRequestOperations by friendRequestService,
    FriendshipOperations by friendshipService

/**
 * 构造私聊会话 ID，格式 `private:smaller:larger`（D-43）。
 *
 * 提升为包级函数：好友申请子域（[FriendRequestService] 内部）与网关层（[com.nebula.gateway.handler.friend.FriendAddHandler]）
 * 共用同一确定性格式，避免重复实现。
 *
 * @param smaller 较小的用户 ID
 * @param larger 较大的用户 ID
 * @return 私聊会话 ID
 */
fun buildPrivateConvId(smaller: Long, larger: Long): String {
    return "private:$smaller:$larger"
}
