package com.nebula.gateway.handler.chat.send

import com.nebula.common.BizCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * 消息去重 Step — 通过 Redis SETNX 检测重复消息（D-07, D-13）。
 *
 * 流程：
 * 1. 使用 `SETNX chat:dedup:{client_msg_id} "pending"` 检测是否为新消息
 * 2. 如果 SETNX 返回 false（键已存在），判定为重复消息，抛出 SendMessageException
 * 3. 如果为新消息，设置 7 天 TTL 并返回 true 继续链
 *
 * 去重键初始值为 "pending"，WriteStep 在成功生成 msg_id 后会更新为实际 msg_id（REVIEW-MEDIUM-6）。
 *
 * @param connection Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class DedupStep(
    private val connection: StatefulRedisConnection<String, String>
) : SendMessageStep {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    /**
     * 执行去重检测。
     *
     * @param context Step 链共享上下文
     * @return true 消息未重复，继续下一步
     * @throws SendMessageException 当检测到重复消息时
     */
    override suspend fun execute(context: SendContext): Boolean {
        val dedupKey = "chat:dedup:${context.req.clientMessageId}"

        // D-07: Redis SETNX 原子操作检测重复
        // 初始值写为 "pending"（非最终 msg_id，因为 msg_id 尚未生成）
        // setnx 返回 Boolean? — null 时当作 set 失败处理
        val isNew = redis.setnx(dedupKey, "pending") ?: false

        if (!isNew) {
            // 重复消息，终止链（D-07 忽略重复）
            throw SendMessageException(BizCode.SEND_FAILED, "重复消息")
        }

        // 设置 7 天 TTL 限制重放窗口
        redis.expire(dedupKey, 7 * 24 * 3600L)

        return true
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
