package com.nebula.gateway.delivery

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息投递三态跟踪服务（D-70, D-71, D-72）。
 *
 * 封装消息投递状态的三态转换逻辑：
 * - sent（0）：服务端已向接收者投递消息
 * - delivered（1）：接收者客户端已确认收到
 * - read（2）：接收者已读消息
 *
 * 状态转换规则：
 * - sent → delivered：正常投递确认路径
 * - sent → read：允许跳级（如接收者在离线期间阅读消息，上线后直接上报已读）
 * - delivered → read：正常已读路径
 * - 已抵达 delivered 或 read 状态时，重复的 delivered 标记返回 false
 * - 已抵达 read 状态时，重复的 read 标记返回 false
 *
 * @param tracker Redis 低层投递状态操作封装
 */
class DeliveryTrackingService(
    private val tracker: RedisDeliveryTracker
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /** 投递状态常量：服务端已投递 */
        private const val STATUS_SENT = 0
        /** 投递状态常量：客户端已确认送达 */
        private const val STATUS_DELIVERED = 1
        /** 投递状态常量：已读 */
        private const val STATUS_READ = 2
    }

    /**
     * 标记消息为"已投递"状态（sent）。
     *
     * 服务端向接收者推送消息后调用。无条件写入，因为 sent 是最低状态。
     *
     * @param msgId 消息 ID
     * @param uid 接收者用户 ID
     * @return true 标记成功
     */
    suspend fun markSent(msgId: Long, uid: Long): Boolean {
        return tracker.setStatus(msgId, uid, STATUS_SENT)
    }

    /**
     * 标记消息为"已送达"状态（delivered）。
     *
     * 接收者客户端回复 DeliveryAck 后调用。
     * 如果当前状态已经是 delivered 或 read，则返回 false 拒绝降级。
     *
     * @param msgId 消息 ID
     * @param uid 接收者用户 ID
     * @return true 标记成功，false 状态不可降级
     */
    suspend fun markDelivered(msgId: Long, uid: Long): Boolean {
        val current = tracker.getStatus(msgId, uid)
        if (current != null && current >= STATUS_DELIVERED) {
            // 状态已为 delivered 或 read，不可降级
            return false
        }
        return tracker.setStatus(msgId, uid, STATUS_DELIVERED)
    }

    /**
     * 标记消息为"已读"状态（read）。
     *
     * 接收者上报已读报告后调用。
     * 允许从 sent 跳级到 read（离线期间阅读消息的场景）。
     * 如果当前状态已经是 read，则返回 false。
     *
     * @param msgId 消息 ID
     * @param uid 接收者用户 ID
     * @return true 标记成功，false 状态不可降级
     */
    suspend fun markRead(msgId: Long, uid: Long): Boolean {
        val current = tracker.getStatus(msgId, uid)
        if (current != null && current >= STATUS_READ) {
            // 状态已为 read，不可降级
            return false
        }
        return tracker.setStatus(msgId, uid, STATUS_READ)
    }
}
