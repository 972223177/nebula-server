package com.nebula.gateway.session

import com.nebula.chat.Envelope
import io.grpc.stub.StreamObserver

/**
 * 可投递的 StreamObserver 接口 — 统一消息发送的串行化入口（G-02/G-03/C-01 修复）。
 *
 * 设计动机：
 * gRPC MessageFramer 非线程安全，多个协程并发调用 [StreamObserver.onNext] 会踩坏缓冲区。
 * 所有向客户端发送 Envelope 的路径必须经过 Mutex 串行化，不能裸调 `onNext()`。
 *
 * 实现方（如 `ChatService.ChatStreamObserver`）通过 [deliver] 方法保证线程安全。
 * 外部调用方（如 `PushService`）应优先检查 `observer is DeliverableStreamObserver`，
 * 命中则走 [deliver]，未命中则降级为 `onNext()`（兼容测试 mock 或其他实现）。
 *
 * 典型用法：
 * ```kotlin
 * val observers = userStreamRegistry.getStreams(uid)
 * for (observer in observers) {
 *     (observer as? DeliverableStreamObserver)?.deliver(envelope) ?: observer.onNext(envelope)
 * }
 * ```
 */
interface DeliverableStreamObserver : StreamObserver<Envelope> {

    /**
     * 线程安全地投递 Envelope 到客户端。
     *
     * 实现方内部通过 Mutex 串行化对 gRPC StreamObserver.onNext() 的调用，
     * 确保不会与同一连接上的其他发送路径并发写入。
     *
     * 非 suspend 函数 — 实现方内部使用 `runBlocking` 桥接协程锁，
     * 因为 `onNext()` 本质是将字节写入 Netty 缓冲区（非阻塞），不会造成协程饥饿。
     *
     * @param envelope 待投递的 Envelope
     */
    fun deliver(envelope: Envelope)
}
