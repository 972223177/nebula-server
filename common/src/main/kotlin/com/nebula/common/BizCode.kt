package com.nebula.common

/**
 * 业务状态码枚举，按领域划分编码段：
 * - 2xx：通用成功
 * - 10xx：通用参数 / 鉴权 / 限流
 * - 11xx：Token / 认证 / 设备
 * - 12xx：用户
 * - 13xx：好友
 * - 14xx：会话 / 群组
 * - 15xx：消息
 * - 9xxx：系统内部异常（数据库、缓存等）
 *
 * 每个枚举值包含整数码（code）和前端展示文案（msg）。
 */
enum class BizCode(val code: Int, val msg: String) {
    /** 请求成功，通用正常响应 */
    OK(200, "ok"),
    /** 请求参数校验失败，参数格式或必填项不通过 */
    INVALID_PARAM(1000, "invalid param"),
    /** 请求未携带有效认证凭证 */
    UNAUTHORIZED(1001, "unauthorized"),
    /** 已认证但无当前操作权限 */
    FORBIDDEN(1002, "forbidden"),
    /** 请求资源不存在 */
    NOT_FOUND(1003, "not found"),
    /** 触发限流，请求被拒绝 */
    RATE_LIMITED(1004, "rate limited"),
    /** 登录 Token 已过期，需刷新 */
    TOKEN_EXPIRED(1100, "token expired"),
    /** 登录 Token 格式或签名不合法 */
    TOKEN_INVALID(1101, "token invalid"),
    /** 认证流程失败，如密码错误 */
    AUTH_FAILED(1102, "auth failed"),
    /** 客户端设备不被服务器支持 */
    DEVICE_UNSUPPORTED(1103, "device unsupported"),
    /** 目标用户不存在 */
    USER_NOT_FOUND(1200, "user not found"),
    /** 注册时用户名已被占用 */
    USERNAME_EXISTS(1201, "username exists"),
    /** 好友请求不存在 */
    REQUEST_NOT_FOUND(1300, "friend request not found"),
    /** 好友请求已被处理（接受或拒绝），不可重复操作 */
    REQUEST_HANDLED(1301, "friend request already handled"),
    /** 不能添加自己为好友 */
    SELF_FRIEND(1302, "cannot add self as friend"),
    /** 双方已经是好友关系 */
    ALREADY_FRIEND(1303, "already friends"),
    /** 非好友关系，无法执行好友专属操作 */
    NOT_FRIEND(1304, "not friend"),
    /** 好友记录不存在 */
    FRIEND_NOT_FOUND(1305, "friend not found"),
    /** 指定会话不存在 */
    CONV_NOT_FOUND(1400, "conversation not found"),
    /** 群组成员已满，无法加入 */
    GROUP_FULL(1401, "group is full"),
    /** 群组已解散 */
    GROUP_DISSOLVED(1402, "group dissolved"),
    /** 非群组成员，无权访问群内资源 */
    NOT_MEMBER(1403, "not a group member"),
    /** 群组成员无权限执行该操作（如管理员操作） */
    GROUP_PERM_DENIED(1404, "group permission denied"),
    /** 用户已在群组中，无需重复加入 */
    ALREADY_IN_GROUP(1405, "user already in group"),
    /** 消息不存在 */
    MSG_NOT_FOUND(1500, "message not found"),
    /** 消息投递失败（如目标离线且不在线队列满） */
    SEND_FAILED(1501, "message send failed"),
    /** 不支持的 message 类型，无法序列化或反序列化 */
    UNSUPPORTED_MSG_TYPE(1502, "unsupported message type"),
    /** 消息内容违反安全或合规策略 */
    CONTENT_VIOLATION(1503, "content violation"),
    /** 服务器内部未预期异常 */
    INTERNAL_ERROR(9000, "internal error"),
    /** 数据库操作失败，如连接超时或约束冲突 */
    DB_ERROR(9001, "database error"),
    /** 缓存操作失败，如 Redis 不可用 */
    CACHE_ERROR(9002, "cache error");

    companion object {
        /**
         * 根据整数码查找对应的 [BizCode] 枚举值。
         *
         * 遍历所有枚举条目，匹配 [BizCode.code] 与输入参数一致的条目。
         * 若找不到匹配则返回 null，由调用方决定兜底行为（如返回 UNKNOWN 或抛异常）。
         *
         * @param code 业务状态码
         * @return 匹配的 [BizCode] 枚举值，未匹配返回 null
         */
        fun fromCode(code: Int): BizCode? = entries.find { it.code == code }
    }
}
