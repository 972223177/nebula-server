package com.nebula.repository.dao

import com.nebula.repository.entity.FriendRequestEntity
import jakarta.persistence.EntityManager

/**
 * 好友请求数据访问对象（替代原 FriendRequestRepository）。
 *
 * 所有方法接收 [EntityManager] 参数，事务由调用方（通常 [JpaTxRunner]）管理。
 */
class FriendRequestDao : EntityDao<FriendRequestEntity>(FriendRequestEntity::class.java) {

    /**
     * 按接收方 UID 和状态查询好友申请列表（收到的申请）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param toUid 申请接收方 UID
     * @param status 申请状态
     * @return 匹配的申请列表
     */
    suspend fun findByToUidAndStatus(
        em: EntityManager,
        toUid: Long,
        status: Int
    ): List<FriendRequestEntity> = queryList(
        em,
        "SELECT fr FROM FriendRequestEntity fr WHERE fr.toUid = :toUid AND fr.status = :status",
        "toUid" to toUid,
        "status" to status
    )

    /**
     * 按发起方 UID 和状态查询好友申请列表（发出的申请）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param fromUid 申请发起方 UID
     * @param status 申请状态
     * @return 匹配的申请列表
     */
    suspend fun findByFromUidAndStatus(
        em: EntityManager,
        fromUid: Long,
        status: Int
    ): List<FriendRequestEntity> = queryList(
        em,
        "SELECT fr FROM FriendRequestEntity fr WHERE fr.fromUid = :fromUid AND fr.status = :status",
        "fromUid" to fromUid,
        "status" to status
    )

    /**
     * H3 批量优化：按目标 uid 列表和当前 uid 双向查找所有 pending 申请。
     *
     * 一条查询替代 N 次 findByFromUidAndToUidAndStatus，用于批量关系查询。
     * 查询方向：(fromUid=uid AND toUid IN targets) OR (fromUid IN targets AND toUid=uid)
     *
     * @param em 当前事务的 [EntityManager]
     * @param uid 当前用户 UID
     * @param targetUids 目标用户 UID 列表
     * @return 双向 pending 申请列表
     */
    suspend fun findAllPendingBidirectional(
        em: EntityManager,
        uid: Long,
        targetUids: List<Long>
    ): List<FriendRequestEntity> {
        if (targetUids.isEmpty()) return emptyList()
        return queryList(
            em,
            """
            SELECT fr FROM FriendRequestEntity fr 
            WHERE fr.status = 0 
            AND ((fr.fromUid = :uid AND fr.toUid IN :targetUids) 
                 OR (fr.toUid = :uid AND fr.fromUid IN :targetUids))
            """.trimIndent(),
            "uid" to uid,
            "targetUids" to targetUids
        )
    }

    /**
     * H3 批量优化：查找双向任意状态的申请（用于被拒绝检测）。
     */
    suspend fun findAllBidirectional(
        em: EntityManager,
        uid: Long,
        targetUids: List<Long>
    ): List<FriendRequestEntity> {
        if (targetUids.isEmpty()) return emptyList()
        return queryList(
            em,
            """
            SELECT fr FROM FriendRequestEntity fr 
            WHERE ((fr.fromUid = :uid AND fr.toUid IN :targetUids) 
                   OR (fr.toUid = :uid AND fr.fromUid IN :targetUids))
            """.trimIndent(),
            "uid" to uid,
            "targetUids" to targetUids
        )
    }

    /**
     * 按发起方和接收方精确查找好友申请。
     *
     * @param em 当前事务的 [EntityManager]
     * @param fromUid 申请发起方 UID
     * @param toUid 申请接收方 UID
     * @return 好友申请实体，不存在返回 null
     */
    suspend fun findByFromUidAndToUid(
        em: EntityManager,
        fromUid: Long,
        toUid: Long
    ): FriendRequestEntity? = querySingle(
        em,
        "SELECT fr FROM FriendRequestEntity fr WHERE fr.fromUid = :fromUid AND fr.toUid = :toUid",
        "fromUid" to fromUid,
        "toUid" to toUid
    )

    /**
     * 按发送方、接收方和状态精确查询（D-51 重复申请检查、D-52 双向竞赛检测）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param fromUid 申请发起方 UID
     * @param toUid 申请接收方 UID
     * @param status 申请状态
     * @return 好友申请实体，不存在返回 null
     */
    suspend fun findByFromUidAndToUidAndStatus(
        em: EntityManager,
        fromUid: Long,
        toUid: Long,
        status: Int
    ): FriendRequestEntity? = querySingle(
        em,
        "SELECT fr FROM FriendRequestEntity fr WHERE fr.fromUid = :fromUid AND fr.toUid = :toUid AND fr.status = :status",
        "fromUid" to fromUid,
        "toUid" to toUid,
        "status" to status
    )
}
