package com.nebula.server.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.config.buildSslContext
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit

/**
 * gRPC Netty 服务管理器 — 封装服务的启动、停止和生命周期管理。
 *
 * 职责：
 * - 使用 NettyServerBuilder 构建 gRPC Server
 * - 集成 SSL/TLS 双向证书认证（可选）
 * - 配置连接控制参数：消息大小限制、keepalive、流控
 * - 提供服务启动、优雅关闭和阻塞等待的声明式接口
 *
 * 设计决策引用：
 * - D-09: gRPC + TLSv1.2/TLSv1.3 加密传输
 * - D-10: Netty 作为 gRPC 传输层
 * - INFRA-02: gRPC Netty 启动入口
 */
class ChatServer(private val config: ApplicationConfig) {

    private var server: Server? = null

    /**
     * 启动 gRPC 服务。
     *
     * 构建流程：
     * 1. 生成 SSLContext（若 ssl.enabled=false 则返回 null，不加载证书）
     * 2. 配置 NettyServerBuilder：端口、消息大小、keepalive 参数
     * 3. 有条件地配置 SSL（sslContext != null 时启用）
     * 4. 调用 builder.build().start() 启动服务
     *
     * @throws IOException 如果端口被占用或 SSL 证书加载失败
     */
    fun start() {
        val sslContext = config.ssl.buildSslContext()

        val builder = NettyServerBuilder.forPort(config.server.port)
            .maxInboundMessageSize(4 * 1024 * 1024) // 最大入站消息 4MB，超过则拒绝连接
            // 双重心跳策略 — 传输层 gRPC keepalive 快速检测 TCP 断开
            // 参考业界标准（Go gRPC）精细化配置，详见 D-27
            .keepAliveTime(60, TimeUnit.SECONDS)        // 每 60s 向客户端发送 PING 帧检测存活
            .keepAliveTimeout(20, TimeUnit.SECONDS)     // 等待 PONG 超时 20s，超时则断开
            .permitKeepAliveWithoutCalls(true)          // 允许无活跃 RPC 时发送 PING（D-27 双重心跳必须开启）
            .maxConnectionIdle(300, TimeUnit.SECONDS)   // 空闲超过 5 分钟主动关闭连接释放资源
            .maxConnectionAge(1800, TimeUnit.SECONDS)   // 30 分钟强制刷新连接，防老化、利负载均衡
            .maxConnectionAgeGrace(10, TimeUnit.SECONDS)// 强制关闭前等待 10s，给进行中请求留缓冲

        // 若 SSL 开启，将生成的 SslContext 注入 Netty 管道
        sslContext?.let { builder.sslContext(it) }

        server = builder.build().start()
        println("[Nebula] gRPC server started on port ${config.server.port}" +
                if (config.ssl.enabled) " (SSL enabled)" else "")
    }

    /**
     * 优雅关闭 gRPC 服务。
     *
     * 调用 shutdown() 后，服务端停止接受新请求，并等待已有 RPC 完成。
     * awaitTermination 设置最长等待 5 秒，超时后强制关闭。
     */
    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
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
}
