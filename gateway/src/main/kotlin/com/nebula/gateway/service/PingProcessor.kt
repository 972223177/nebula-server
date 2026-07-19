package com.nebula.gateway.service

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.service.user.OnlineStatusService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PING 心跳处理器（D-27, D-57）。
 *
 * 从 ChatService.handlePing 抽离。PING 是协议级保活，非业务请求，单独成组件职责更清晰。
 * connectionScope 作为方法参数传入（per-connection），绝不注入单例组件，守住"连接态不泄漏"边界。
 *
 * @param onlineStatusService 在线状态服务（刷新 TTL）
 */
internal class PingProcessor(
    private val onlineStatusService: OnlineStatusService,
) {
    private companion object {
        val logger = KotlinLogging.logger {}
    }

    // TODO(D-29): 应用层心跳超时检测 — 90s 无 PING/REQUEST 则断开连接并清理 Session。
    //  当前只做了 PING 的响应（回 PONG + refreshTtl），没有"PING 超时"的定时检查。
    //  单机部署下由传输层 gRPC keepalive（10s 超时）兜底断连判决，此缺口不致命。
    //  多实例负载均衡后需补全：在 ConnectionContext 中记录 lastActivityAt，
    //  定期检查 now - lastActivityAt > 90s → onError() 断连。
    //  REQUEST 和 PING 均视为活跃（重置 lastActivityAt）。
    /**
     * 处理 PING 心跳请求，回复 PONG Envelope + 刷新在线状态 TTL（D-27, D-57）。
     *
     * @param envelope PING 请求 Envelope
     * @param observer 当前连接句柄（提供 connectionScope / sendEnvelope）
     */
    fun handle(envelope: Envelope, observer: ConnectionContext) {
        val connId = "#${observer.connId}"

        // D-57: 刷新在线状态 TTL。connectionScope 跟随连接生命周期
        observer.userId?.let { uid ->
            observer.connectionScope.launch {
                withContext(Dispatchers.IO) {
                    onlineStatusService.refreshTtl(uid)
                }
            }
        }

        val pongEnvelope = Envelope.newBuilder()
            .setDirection(Direction.PONG)
            .setRequestId(envelope.requestId)
            .build()

        // D-85: PONG 发送通过 connectionScope.launch 桥接；发送失败则彻底关闭连接避免悬死。
        observer.connectionScope.launch {
            try {
                observer.sendEnvelope(pongEnvelope)
            } catch (e: Exception) {
                logger.error(e) { "[heartbeat] $connId PONG 发送失败，关闭连接" }
                try {
                    observer.onCompleted()
                } catch (cleanupEx: Exception) {
                    logger.error(cleanupEx) { "[heartbeat] $connId PONG 失败后关闭连接异常" }
                }
            }
        }
    }
}
