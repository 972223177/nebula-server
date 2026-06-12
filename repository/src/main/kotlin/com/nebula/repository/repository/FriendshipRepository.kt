package com.nebula.repository.repository

import com.nebula.repository.entity.FriendshipEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 好友关系数据仓库。
 */
interface FriendshipRepository : JpaRepository<FriendshipEntity, Long> {
    fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendshipEntity?
    fun findByUserId(userId: Long): List<FriendshipEntity>
}
