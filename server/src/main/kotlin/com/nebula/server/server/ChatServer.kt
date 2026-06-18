package com.nebula.server.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.config.buildSslContext
import com.nebula.gateway.service.ChatService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ForwardingServerCallListener
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * gRPC Netty 服务管理器 — 封装服务的启动、停止和生命周期管理。
 *
 * 职责：
 * - 使用 NettyServerBuilder 构建 gRPC Server
 * - 集成 SSL/TLS 双向证书认证（可选）
 * - 配置连接控制参数：消息大小限制、keepalive、流控
 * - 注册 gRPC 服务端点（addService）
 * - 提供服务启动、优雅关闭和阻塞等待的声明式接口
 *
 * 设计决策引用：
 * - D-09: gRPC + TLSv1.2/TLSv1.3 加密传输
 * - D-10: Netty 作为 gRPC 传输层
 * - INFRA-02: gRPC Netty 启动入口
 */
class ChatServer(private val config: ApplicationConfig) {

    /** gRPC Server 实例，start() 后非 null，stop() 后置 null。使用 @Volatile 保证 Shutdown Hook 线程可见性（CQ-11）。 */
    @Volatile
    private var server: Server? = null

    /** 连接计数器，用于分配连接编号 */
    private val connectionCounter = AtomicLong(0)

    /**
     * 调试拦截器 — 记录每次 gRPC 调用（双向流连接建立）的连接信息和协议细节。
     *
     * 打印内容：
     * - 远程 IP:port（从 Netty transport attributes 获取）
     * - 调用的方法全名
     * - 连接序号（用于追踪同一个流上的多帧消息）
     */
    private val debugInterceptor = object : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>
        ): ServerCall.Listener<ReqT> {
            // 从 Netty transport attrs 获取远程地址
            val remoteAddr: SocketAddress? = call.attributes[Grpc.TRANSPORT_ATTR_REMOTE_ADDR]
            val remoteStr = when (remoteAddr) {
                is InetSocketAddress -> "${remoteAddr.hostString}:${remoteAddr.port}"
                else -> remoteAddr?.toString() ?: "unknown"
            }
            val connId = connectionCounter.incrementAndGet()
            val authority = call.authority ?: "unknown"

            logger.info { "[transport] #$connId 新连接建立 remote=$remoteStr authority=$authority method=${call.methodDescriptor.fullMethodName}" }

            // 包装 ServerCall，在 close 时记录日志
            val wrappedCall = object : ServerCall<ReqT, RespT>() {
                override fun request(numMessages: Int) = call.request(numMessages)
                override fun sendHeaders(headers: Metadata) = call.sendHeaders(headers)
                override fun sendMessage(message: RespT) = call.sendMessage(message)
                override fun close(status: Status, trailers: Metadata) {
                    logger.info { "[transport] #$connId 连接关闭 remote=$remoteStr status=${status.code} desc=${status.description}" }
                    call.close(status, trailers)
                }
                override fun isCancelled(): Boolean = call.isCancelled
                override fun getMethodDescriptor(): MethodDescriptor<ReqT, RespT> = call.methodDescriptor
                override fun isReady(): Boolean = call.isReady
                override fun setMessageCompression(enabled: Boolean) = call.setMessageCompression(enabled)
                override fun setCompression(compressor: String) = call.setCompression(compressor)
                override fun getAttributes(): io.grpc.Attributes = call.attributes
                override fun getAuthority(): String? = call.authority
                override fun getSecurityLevel(): io.grpc.SecurityLevel = call.securityLevel
            }
            val listener = next.startCall(wrappedCall, headers)

            // 包装 listener 以记录每个收到的消息
            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
                private var messageCount = AtomicLong(0)

                override fun onMessage(message: ReqT) {
                    val seq = messageCount.incrementAndGet()
                    logger.info { "[transport] #$connId 收到消息 #$seq remote=$remoteStr messageClass=${message?.javaClass?.simpleName ?: "null"}" }
                    super.onMessage(message)
                }

                override fun onHalfClose() {
                    logger.info { "[transport] #$connId client half-close remote=$remoteStr totalMessages=${messageCount.get()}" }
                    super.onHalfClose()
                }

                override fun onCancel() {
                    logger.info { "[transport] #$connId client cancelled remote=$remoteStr totalMessages=${messageCount.get()}" }
                    super.onCancel()
                }
            }
        }
    }

    /**
     * 启动 gRPC 服务，注册 ChatService 服务端点。
     *
     * 构建流程：
     * 1. 生成 SSLContext（若 ssl.enabled=false 则返回 null，不加载证书）
     * 2. 配置 NettyServerBuilder：端口、消息大小、keepalive 参数、addService 服务注册
     * 3. 有条件地配置 SSL（sslContext != null 时启用）
     * 4. 调用 builder.build().start() 启动服务
     *
     * 修复 Phase 4 遗漏的 addService 注册（Review 反馈#高优先级）：
     * ChatService 作为 gRPC BindableService，需通过 builder.addService() 注册到 gRPC Server。
     *
     * @param chatService ChatService 双向流服务实例
     * @throws IOException 如果端口被占用或 SSL 证书加载失败
     */
    fun start(chatService: ChatService) {
        val sslContext = config.ssl.buildSslContext()

        val builder = NettyServerBuilder.forPort(config.server.port)
            .maxInboundMessageSize(4 * 1024 * 1024) // 最大入站消息 4MB，超过则拒绝连接

            // 双重心跳策略 — 传输层 + 应用层（D-27）
            // 传输层 gRPC keepalive → 连接存活检测（TCP/HTTP2 帧层面）
            // 应用层 system/ping → 服务健康检测（业务请求链路层面）
            // 两者互补：gRPC keepalive 只说明帧层面连接通，应用层 PING 说明整个链路正常工作

            // 服务端 EnforcementPolicy — 影响客户端 keepalive 行为
            .keepAliveTime(30, TimeUnit.SECONDS)       // D-29 baseline，实际值会在创建时随机化 30~45s（D-32 Jitter 防惊群）
            .keepAliveTimeout(10, TimeUnit.SECONDS)    // PING 超时 10s，超时即断开
            .permitKeepAliveWithoutCalls(true)          // 无活动 RPC 也发 PING，双重心跳必须开启（D-27）

            // 服务端 MaxConnectionIdle — 主动回收僵尸连接
            .maxConnectionIdle(10, TimeUnit.MINUTES)  // D-29 baseline，实际值会在创建时随机化 10~30min（D-32 Jitter）

            // 安全边界 — 强制执行连接生命周期上限
            .maxConnectionAge(1800, TimeUnit.SECONDS)   // 30 分钟强制刷新连接，防老化、利负载均衡（安全边界，保持不变）
            .maxConnectionAgeGrace(10, TimeUnit.SECONDS)// 强制关闭前等待 10s，给进行中请求留缓冲

            // 调试拦截器 — 记录每个 gRPC 连接的建立/关闭/消息接收（transport 层日志）
            .intercept(debugInterceptor)

            // Phase 5: 注册 ChatService 服务端点（Review 修复：Phase 4 遗漏的 addService）
            .addService(chatService)

        // 若 SSL 开启，将生成的 SslContext 注入 Netty 管道
        sslContext?.let { builder.sslContext(it) }

        server = builder.build().start()
        logger.info { "gRPC 服务已启动，端口: ${config.server.port}" + if (config.ssl.enabled) " (SSL 已启用)" else "" }
    }

    /**
     * 优雅关闭 gRPC 服务。
     *
     * 调用 shutdown() 后，服务端停止接受新请求，并等待已有 RPC 完成。
     * awaitTermination 设置最长等待 30 秒，给高负载场景下缓冲消息足够的写入时间，
     * 超时后强制关闭。
     */
    fun stop() {
        server?.shutdown()?.awaitTermination(30, TimeUnit.SECONDS)
    }

    /**
     * 阻塞当前线程直到服务关闭。
     *
     * 通常在 main 线程中调用，使进程保持运行直到收到 SIGTERM 等关闭信号。
     * 当 gRPC Server 的 shutdown() 被触发后，awaitTermination() 返回。
     */
    fun blockUntilShutdown() {
        server?.awaitTermination()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
