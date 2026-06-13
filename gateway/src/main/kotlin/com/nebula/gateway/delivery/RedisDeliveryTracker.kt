package com.nebula.gateway.delivery

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.KeyValue
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.flow.toList

/**
 * Redis 消息投递状态跟踪 — 低层 Hash 操作封装（D-70, D-71, D-72）。
 *
 * 使用 Redis Hash 存储每条消息在每个接收者处的投递状态：
 * - Key 格式: `msg:{msg_id}:delivery`
 * - Field 格式: `{uid}:status`
 * - Value 含义: 0=sent（服务端已投递）、1=delivered（客户端已确认送达）、2=read（已读）
 *
 * 每条消息的投递状态过期时间为 7 天，与消息去重窗口一致。
 *
 * @param connection 共享 Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisDeliveryTracker(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        /** Redis Hash key 前缀 */
        private const val KEY_PREFIX = "msg:"
        /** Redis Hash key 后缀 */
        private const val SUFFIX = ":delivery"
        /** TTL：7 天 */
        private const val TTL_SECONDS = 7 * 24 * 3600L
    }

    /**
     * 设置指定消息在指定用户处的投递状态。
     *
     * @param msgId 消息 ID
     * @param uid 用户 ID
     * @param status 投递状态（0=sent, 1=delivered, 2=read）
     * @return true 设置成功，false 设置失败
     */
    suspend fun setStatus(msgId: Long, uid: Long, status: Int): Boolean {
        val result = redis.hset(key(msgId), field(uid), status.toString())
        redis.expire(key(msgId), TTL_SECONDS)
        return result ?: false
    }

    /**
     * 查询指定消息在指定用户处的当前投递状态。
     *
     * @param msgId 消息 ID
     * @param uid 用户 ID
     * @return 投递状态（0=sent, 1=delivered, 2=read），键不存在时返回 null
     */
    suspend fun getStatus(msgId: Long, uid: Long): Int? {
        return redis.hget(key(msgId), field(uid))?.toIntOrNull()
    }

    /**
     * 查询指定消息在所有接收者处的投递状态。
     *
     * @param msgId 消息 ID
     * @return 用户 ID 到状态字符串的映射
     */
    suspend fun getAllStatuses(msgId: Long): Map<String, String> {
        return redis.hgetall(key(msgId))
            .toList()
            .associate { it.key to it.value }
    }

    /**
     * 刷新指定消息投递状态 Hash 的 TTL。
     *
     * @param msgId 消息 ID
     */
    suspend fun refreshTtl(msgId: Long) {
        redis.expire(key(msgId), TTL_SECONDS)
    }

    /** 构造 Redis Hash key */
    private fun key(msgId: Long): String = "$KEY_PREFIX${msgId}$SUFFIX"

    /** 构造 Hash field */
    private fun field(uid: Long): String = "$uid:status"
}
