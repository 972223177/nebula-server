package com.nebula.service.sequence

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * 会话消息序列号服务 — 基于 Redis INCR 的序列号生成（Phase 10）。
 *
 * 为每个会话的每个成员维护一个单调递增的序列号，用于客户端检测消息序列号间隙。
 * Redis Key 模式：seq:conv:{convId}:next_seq:uid:{uid}
 *
 * 当序列号接近 Long.MAX_VALUE 时自动重置，防止溢出。
 *
 * @param connection Lettuce Redis 连接
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SeqService(
    private val connection: StatefulRedisConnection<String, String>
) {

    /** Lettuce 协程 Redis 命令接口，通过 connection.reactive() 构造 */
    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        /** Redis Key 前缀：seq:conv: */
        const val KEY_PREFIX = "seq:conv:"

        /** Redis Key 后缀：:next_seq:uid: */
        const val SUFFIX = ":next_seq:uid:"

        /** 最大序列号阈值，超过此值时重置为 1，留出 10000 的缓冲空间防止并发溢出 */
        const val MAX_SEQ_THRESHOLD = Long.MAX_VALUE - 10000

        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
    }

    /**
     * 构建 Redis Key。
     *
     * @param convId 会话 ID
     * @param uid 用户 ID
     * @return Redis Key 字符串
     */
    private fun key(convId: String, uid: Long): String = "$KEY_PREFIX$convId$SUFFIX$uid"

    /**
     * 获取会话下一条序列号并自增。
     *
     * 检查当前值是否接近 [Long.MAX_VALUE]，如果是则重置为 1 再 INCR，
     * 防止 Key 在长时间运行后溢出。
     *
     * @param convId 会话 ID
     * @param uid 用户 ID
     * @return 新的序列号
     */
    suspend fun nextSeq(convId: String, uid: Long): Long {
        val redisKey = key(convId, uid)

        // 检查当前值是否接近溢出阈值
        val current = redis.get(redisKey)
        if (current != null) {
            val currentVal = current.toLongOrNull()
            if (currentVal != null && currentVal >= MAX_SEQ_THRESHOLD) {
                logger.warn { "序列号即将溢出，重置 Key=$redisKey，当前值=$currentVal" }
                redis.set(redisKey, "1")
            }
        }

        return redis.incr(redisKey) ?: 0L
    }

    /**
     * 查询会话当前序列号。
     *
     * @param convId 会话 ID
     * @param uid 用户 ID
     * @return 当前序列号，Key 不存在时返回 0
     */
    suspend fun currentSeq(convId: String, uid: Long): Long {
        val redisKey = key(convId, uid)
        val value = redis.get(redisKey)
        if (value == null) {
            return 0L
        }
        return value.toLongOrNull() ?: 0L
    }

    /**
     * 尝试从数据库恢复 Redis 序列号（D-81, H21）。
     *
     * 使用 SETNX 仅在 Key 不存在时设置，避免覆盖 Redis 中已有的序列号。
     * 在服务启动时调用，确保 Redis 重启后序列号从 MySQL 消息计数恢复。
     *
     * @param convId 会话 ID
     * @param uid 用户 ID
     * @param nextSeq 从数据库计算的起始序列号
     * @return true 表示 Key 不存在且已设置，false 表示 Key 已存在（未被覆盖）
     */
    suspend fun tryRestoreSeq(convId: String, uid: Long, nextSeq: Long): Boolean {
        val redisKey = key(convId, uid)
        return redis.setnx(redisKey, nextSeq.toString()) ?: false
    }

    /**
     * 从 MySQL 恢复所有会话的 Redis 序列号（D-81/H21，D-28 重构版）。
     *
     * 原 [ServerBootstrap.recoverSequences] 直接依赖 repository 层的
     * ConversationRepository/MessageRepository/ConversationMemberRepository。
     * 此方法使用 service 层接口，接受查询闭包以解耦。
     *
     * @param conversationSupplier 获取所有会话的函数（返回 Pair<会话ID, 类型> 的列表）
     * @param msgCountByConv 按会话 ID 统计消息数量的函数
     * @param memberSupplier 按会话 ID 获取成员列表的函数（返回成员 UID 列表）
     * @return 已恢复的序列号 Key 数量
     */
    suspend fun recoverSequences(
        conversationSupplier: suspend () -> List<Pair<String, Int>>,
        msgCountByConv: suspend (String) -> Long,
        memberSupplier: suspend (String) -> List<Long>
    ): Int {
        logger.info { "开始从 MySQL 恢复 Redis 序列号..." }
        var restoredCount = 0

        val conversations = conversationSupplier()
        conversations.forEach { (convId, _) ->
            val msgCount = msgCountByConv(convId)
            val nextSeq = msgCount + 1L

            val memberIds = memberSupplier(convId)
            memberIds.forEach { uid ->
                val restored = tryRestoreSeq(convId, uid, nextSeq)
                if (restored) restoredCount++
            }
        }

        logger.info { "序列号恢复完成: $restoredCount 个 Key 已初始化（共 ${conversations.size} 个会话）" }
        return restoredCount
    }
}
