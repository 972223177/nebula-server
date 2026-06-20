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
     * 按接收方 UID 和状态查询好友申请列表。
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
