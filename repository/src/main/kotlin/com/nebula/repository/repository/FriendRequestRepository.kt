package com.nebula.repository.repository

import com.nebula.repository.entity.FriendRequestEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 好友请求数据仓库。
 */
interface FriendRequestRepository : JpaRepository<FriendRequestEntity, Long> {
    /**
     * 按接收方 UID 和状态查询好友申请列表。
     *
     * @param toUid 申请接收方 UID
     * @param status 申请状态
     * @return 匹配的申请列表
     */
    fun findByToUidAndStatus(toUid: Long, status: Int): List<FriendRequestEntity>
    /**
     * 按发起方和接收方精确查找好友申请。
     *
     * @param fromUid 申请发起方 UID
     * @param toUid 申请接收方 UID
     * @return 好友申请实体，不存在返回 null
     */
    fun findByFromUidAndToUid(fromUid: Long, toUid: Long): FriendRequestEntity?

    /**
     * 按发送方、接收方和状态精确查询（D-51 重复申请检查、D-52 双向竞赛检测）。
     */
    fun findByFromUidAndToUidAndStatus(fromUid: Long, toUid: Long, status: Int): FriendRequestEntity?

    /**
     * 查询待处理的好友申请列表，按创建时间降序排列（D-41）。
     */
    fun findByToUidAndStatusOrderByCreatedAtDesc(toUid: Long, status: Int): List<FriendRequestEntity>
}
