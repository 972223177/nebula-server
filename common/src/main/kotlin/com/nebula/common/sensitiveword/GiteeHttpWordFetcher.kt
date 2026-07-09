package com.nebula.common.sensitiveword

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 基于 JDK 内置 HttpClient 的敏感词库拉取器（[SensitiveWordFetcher] 的默认实现，D-111）。
 *
 * 设计决策：
 * - 使用 JDK 11+ 标准库 `java.net.http.HttpClient`，零额外依赖，符合项目克制引入依赖的风格
 * - 网络 I/O 涉及阻塞调用，通过 `withContext(Dispatchers.IO)` 切换线程，避免阻塞协程调度器
 * - 跟随重定向（Gitee raw 可能 302 跳转），超时 30s 防止无限挂起
 * - 源地址为空时直接抛异常，交由上层降级为"空词库启动"
 *
 * @property sourceUrl 词库 raw 文本文件地址（一行一词）
 */
class GiteeHttpWordFetcher(
    private val sourceUrl: String
) : SensitiveWordFetcher {

    /** 复用单例 HttpClient，避免每次请求重建连接与线程池 */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** 响应体字节上限（10MB），超出视为异常并降级保留现有词库，防止 OOM（M3 review） */
    private val maxBodyBytes: Int = 10 * 1024 * 1024

    override suspend fun fetch(): String {
        if (sourceUrl.isBlank()) {
            throw IllegalArgumentException("sensitive-word source-url 未配置")
        }
        return withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(sourceUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body()?.close()
                throw RuntimeException("拉取敏感词库失败: HTTP ${response.statusCode()} url=$sourceUrl")
            }
            // 流式按上限读取，避免 ofString 全量入内存导致大文件 OOM（M3）
            val bytes = response.body().use { readWithLimit(it, maxBodyBytes) }
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * 从输入流按字节上限读取为字节数组。
     *
     * 任意时刻累积字节不超过 [maxBytes] + 单次读取缓冲，超限立即抛异常交由上层降级，
     * 不会因超大响应体而膨胀内存。
     *
     * @param stream 响应体输入流
     * @param maxBytes 允许的最大字节数
     * @return 读取到的字节数组
     * @throws RuntimeException 响应体超过 [maxBytes]
     */
    private fun readWithLimit(stream: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream(maxBytes.coerceAtMost(8192))
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (total + n > maxBytes) {
                throw RuntimeException("敏感词库响应体超过上限 ${maxBytes} 字节，降级保留现有词库")
            }
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
