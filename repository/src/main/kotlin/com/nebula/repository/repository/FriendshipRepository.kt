package com.nebula.repository.repository

import com.nebula.repository.entity.FriendshipEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * 好友关系数据仓库。
 */
interface FriendshipRepository : JpaRepository<FriendshipEntity, Long> {
    /**
     * 按用户 ID 和好友 ID 精确查找好友关系。
     *
     * @param userId 用户 ID
     * @param friendId 好友用户 ID
     * @return 好友关系实体，不存在返回 null
     */
    fun findByUserIdAndFriendId(userId: Long, friendId: Long): FriendshipEntity?

    /**
     * 游标分页查询好友列表（D-46）。
     * 查询所有 userId=? OR friendId=? 且 deleted=0 的记录，按 id DESC 排序。
     *
     * @param userId 当前用户 UID
     * @param cursor 游标，首次传 0 表示从最新开始
     * @param pageable 分页参数（pageSize 由 limit 决定）
     * @return 好友列表，按 id DESC 排序
     */
    @Query("SELECT f FROM FriendshipEntity f WHERE (f.userId = :userId OR f.friendId = :userId) AND f.deleted = 0 AND f.id < :cursor ORDER BY f.id DESC")
    fun findFriendsByUserId(userId: Long, cursor: Long, pageable: Pageable): List<FriendshipEntity>
}
