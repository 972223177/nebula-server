package com.nebula.gateway.service

import com.nebula.chat.Envelope
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 连接上下文接口（Phase 3 瘦身）：将网关子组件与 [ChatService.ChatStreamObserver] inner class 解耦。
 *
 * 仅暴露 per-connection 的必要状态与投递能力，不含任何业务方法：
 * - 连接态：connId / connectionScope / userId / token / deviceType / delayedOfflineJob / deliveryActive
 * - 投递能力：activateDelivery() / sendEnvelope()
 * - 继承 [StreamObserver]：可被存入 tokenToObserver / UserStreamRegistry，且支持 onCompleted() 关闭连接
 *
 * 设计边界（与协程上下文 SessionKey 正交）：
 * - 子组件通过方法参数"借用"此接口操作当前连接，自身永不直接持有连接态，符合"连接态不泄漏到无状态组件"
 * - Handler 仍通过 currentCoroutineContext().requireSession() 取登录态，本接口不参与协程上下文注入
 */
internal interface ConnectionContext : StreamObserver<Envelope> {
    /** 诊断用连接编号 */
    val connId: Long

    /** per-connection 协程作用域，跟随连接生命周期，绝不注入到单例子组件 */
    val connectionScope: CoroutineScope

    /** 已绑定用户 ID（未登录为 null） */
    var userId: Long?

    /** 登录 token（互踢清理用） */
    var token: String?

    /** 设备类型 */
    var deviceType: String?

    /** 延迟离线任务句柄（D-57 重连取消用） */
    var delayedOfflineJob: Job?

    /** 缓存再投递激活标志 */
    var deliveryActive: Boolean

    /** 激活被驱逐连接的缓存再投递（D-67） */
    suspend fun activateDelivery()

    /** 线程安全发送 Envelope（经 sendMutex 串行化） */
    suspend fun sendEnvelope(envelope: Envelope)
}
