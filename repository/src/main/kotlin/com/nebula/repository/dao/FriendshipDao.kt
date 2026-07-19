package com.nebula.repository.dao

import com.nebula.repository.entity.FriendshipEntity
import jakarta.persistence.EntityManager

/**
 * 好友关系数据访问对象（替代原 FriendshipRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 */
class FriendshipDao : EntityDao<FriendshipEntity>(FriendshipEntity::class.java) {

    /**
     * 按用户 ID 和好友 ID 精确查找好友关系。
     *
     * @param em 当前事务的 [EntityManager]
     * @param userId 用户 ID（排序后的较小值）
     * @param friendId 好友用户 ID（排序后的较大值）
     * @return 好友关系实体，不存在返回 null
     */
    fun findByUserIdAndFriendId(
        em: EntityManager,
        userId: Long,
        friendId: Long
    ): FriendshipEntity? = querySingle(
        em,
        "SELECT f FROM FriendshipEntity f WHERE f.userId = :userId AND f.friendId = :friendId",
        "userId" to userId,
        "friendId" to friendId
    )

    /**
     * 游标分页查询好友列表（D-46）。
     * 查询所有 userId=? OR friendId=? 且 deleted=0 的记录，按 id DESC 排序。
     *
     * @param em 当前事务的 [EntityManager]
     * @param userId 当前用户 UID
     * @param cursor 游标（上一页最后一条的 id），首次传 0 表示从最新开始
     * @param limit 返回行数限制
     * @return 好友列表，按 id DESC 排序
     */
    fun findFriendsByUserId(
        em: EntityManager,
        userId: Long,
        cursor: Long,
        limit: Int
    ): List<FriendshipEntity> {
        val query = em.createQuery(
            """
            SELECT f FROM FriendshipEntity f
            WHERE (f.userId = :userId OR f.friendId = :userId)
            AND f.deleted = 0 AND (:cursor = 0 OR f.id < :cursor)
            ORDER BY f.id DESC
            """.trimIndent(),
            FriendshipEntity::class.java
        )
        query.setParameter("userId", userId)
        query.setParameter("cursor", cursor)
        query.maxResults = limit
        return query.resultList
    }

    /**
     * H3 批量优化：批量查询 userId 与 targetUids 中用户的双向好友关系。
     *
     * 一条查询替代 N 次 findByUserIdAndFriendId。
     *
     * @param em 当前事务的 [EntityManager]
     * @param userId 当前用户 UID
     * @param targetUids 目标用户 UID 列表
     * @return 活跃的好友关系实体列表
     */
    fun findAllFriendsByUids(
        em: EntityManager,
        userId: Long,
        targetUids: List<Long>
    ): List<FriendshipEntity> {
        if (targetUids.isEmpty()) return emptyList()
        return queryList(
            em,
            """
            SELECT f FROM FriendshipEntity f
            WHERE f.deleted = 0
            AND ((f.userId = :userId AND f.friendId IN :targetUids)
                 OR (f.friendId = :userId AND f.userId IN :targetUids))
            """.trimIndent(),
            "userId" to userId,
            "targetUids" to targetUids
        )
    }
}
