package com.nebula.common

enum class BizCode(val code: Int, val msg: String) {
    OK(200, "ok"),
    INVALID_PARAM(1000, "invalid param"),
    UNAUTHORIZED(1001, "unauthorized"),
    FORBIDDEN(1002, "forbidden"),
    NOT_FOUND(1003, "not found"),
    RATE_LIMITED(1004, "rate limited"),
    TOKEN_EXPIRED(1100, "token expired"),
    TOKEN_INVALID(1101, "token invalid"),
    AUTH_FAILED(1102, "auth failed"),
    DEVICE_UNSUPPORTED(1103, "device unsupported"),
    USER_NOT_FOUND(1200, "user not found"),
    USERNAME_EXISTS(1201, "username exists"),
    REQUEST_NOT_FOUND(1300, "friend request not found"),
    REQUEST_HANDLED(1301, "friend request already handled"),
    SELF_FRIEND(1302, "cannot add self as friend"),
    ALREADY_FRIEND(1303, "already friends"),
    NOT_FRIEND(1304, "not friend"),
    FRIEND_NOT_FOUND(1305, "friend not found"),
    CONV_NOT_FOUND(1400, "conversation not found"),
    GROUP_FULL(1401, "group is full"),
    GROUP_DISSOLVED(1402, "group dissolved"),
    NOT_MEMBER(1403, "not a group member"),
    GROUP_PERM_DENIED(1404, "group permission denied"),
    ALREADY_IN_GROUP(1405, "user already in group"),
    MSG_NOT_FOUND(1500, "message not found"),
    SEND_FAILED(1501, "message send failed"),
    UNSUPPORTED_MSG_TYPE(1502, "unsupported message type"),
    CONTENT_VIOLATION(1503, "content violation"),
    INTERNAL_ERROR(9000, "internal error"),
    DB_ERROR(9001, "database error"),
    CACHE_ERROR(9002, "cache error");

    companion object {
        fun fromCode(code: Int): BizCode? = entries.find { it.code == code }
    }
}
