package com.nebula.service.friend

/**
 * 好友关系信息 DTO — 供 gateway 层查询好友关系，替代 [com.nebula.repository.entity.FriendshipEntity]。
 *
 * 屏蔽 repository 层的 JPA 实体细节，仅暴露 gateway 层需要的业务字段。
 *
 * @param userId 用户 ID（有序对的较小值）
 * @param friendId 好友 ID（有序对的较大值）
 * @param deleted 软删除标记：0=有效 1=已删除
 */
data class FriendshipInfo(
    val userId: Long,
    val friendId: Long,
    val deleted: Int
)
