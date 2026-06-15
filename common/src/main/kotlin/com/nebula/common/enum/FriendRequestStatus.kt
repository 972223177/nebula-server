package com.nebula.common.enum

/**
 * 好友申请状态枚举（CQ-12, L04）。
 *
 * 对应 FriendRequestEntity.status 字段。
 * 值定义与 SQL DDL COMMENT 一致：0=待处理, 1=已接受, 2=已拒绝。
 */
enum class FriendRequestStatus(val code: Int) {
    /** 待处理 */
    PENDING(0),
    /** 已接受 */
    ACCEPTED(1),
    /** 已拒绝 */
    REJECTED(2);

    companion object {
        fun fromCode(code: Int): FriendRequestStatus = entries.first { it.code == code }
    }
}
