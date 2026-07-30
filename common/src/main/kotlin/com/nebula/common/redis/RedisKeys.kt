package com.nebula.common.redis

/**
 * Redis Key 定义集中管理 — 所有模块（common/repository/service/gateway）通过此入口获取 Key。
 *
 * 每个 key 空间提供：
 * - 前缀常量（PREFIX）
 * - 构造方法（fun key(...)）
 * - SCAN pattern（可选）
 *
 * 所有 key 格式在此集中定义，避免各模块内联拼接导致的不一致（D-05）。
 */
object RedisKeys {

    // ==================== Session 会话 ====================
    /** session:token:{token} — Session Token 主键 */
    const val SESSION_TOKEN_PREFIX = "session:token:"
    /** session:{userId}:{deviceType} — 设备类型映射键 */
    const val SESSION_DEVICE_TYPE_PREFIX = "session:"

    fun sessionTokenKey(token: String) = "$SESSION_TOKEN_PREFIX$token"

    fun deviceTypeMappingKey(userId: Long, deviceType: String) = "$SESSION_DEVICE_TYPE_PREFIX$userId:$deviceType"

    const val DEVICE_TYPE_SCAN_PATTERN = "session:*"

    // ==================== 在线状态 ====================
    /** online:user:{userId} */
    const val ONLINE_PREFIX = "online:user:"

    fun onlineStatusKey(userId: Long) = "$ONLINE_PREFIX$userId"

    // ==================== 隐私设置 ====================
    /** privacy:user:{userId} */
    const val PRIVACY_PREFIX = "privacy:user:"

    fun privacyKey(userId: Long) = "$PRIVACY_PREFIX$userId"

    // ==================== 投递跟踪 ====================
    /** msg:{msgId}:delivery — 消息投递状态 Hash */
    const val MSG_PREFIX = "msg:"
    const val DELIVERY_SUFFIX = ":delivery"

    fun deliveryKey(msgId: Long) = "$MSG_PREFIX$msgId$DELIVERY_SUFFIX"

    // ==================== 消息队列 ====================
    /** Redis Stream 消息队列 key */
    const val QUEUE_STREAM_KEY = "queue:messages"

    // ==================== fan-out 未读推送（§七 Durable Outbox） ====================
    /** fan-out 事件 Redis Stream key（独立于 queue:messages，避免与消息持久化争用消费者组） */
    const val FANOUT_STREAM_KEY = "fanout:stream"

    // ==================== 消息去重 ====================
    /** dedup:msg:{clientMsgId} */
    const val DEDUP_PREFIX = "dedup:msg:"

    fun dedupKey(clientMsgId: String) = "$DEDUP_PREFIX$clientMsgId"

    // ==================== 序列号 ====================
    /** seq:conv:{convId}:next_seq:uid:{uid} */
    const val SEQ_PREFIX = "seq:conv:"
    const val SEQ_SUFFIX = ":next_seq:uid:"

    fun seqKey(convId: String, uid: Long) = "$SEQ_PREFIX$convId$SEQ_SUFFIX$uid"
}
