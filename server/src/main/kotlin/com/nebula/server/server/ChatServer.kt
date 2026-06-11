package com.nebula.server.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.config.buildSslContext
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit

class ChatServer(private val config: ApplicationConfig) {

    private var server: Server? = null

    fun start() {
        val sslContext = config.ssl.buildSslContext()

        val builder = NettyServerBuilder.forPort(config.server.port)
            .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(false)

        sslContext?.let { builder.sslContext(it) }

        server = builder.build().start()
        println("[Nebula] gRPC server started on port ${config.server.port}" +
                if (config.ssl.enabled) " (SSL enabled)" else "")
    }

    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }

    fun blockUntilShutdown() {
        server?.awaitTermination()
    }
}
