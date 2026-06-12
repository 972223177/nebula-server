package com.nebula.repository.repository

import com.nebula.repository.entity.FriendRequestEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 好友请求数据仓库。
 */
interface FriendRequestRepository : JpaRepository<FriendRequestEntity, Long> {
    fun findByToUidAndStatus(toUid: Long, status: Int): List<FriendRequestEntity>
    fun findByFromUidAndToUid(fromUid: Long, toUid: Long): FriendRequestEntity?
}
