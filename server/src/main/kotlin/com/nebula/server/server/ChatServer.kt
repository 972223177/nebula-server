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
