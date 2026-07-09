package com.nebula.gateway.handler.sensitiveword

import com.nebula.chat.sensitiveword.SensitiveWordDownloadReq
import com.nebula.chat.sensitiveword.SensitiveWordDownloadResp
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler

/**
 * 敏感词库下载 Handler — method = "system/sensitive_word_download"（D-118）。
 *
 * 客户端启动后调用，分页拉取服务端内存中的敏感词列表，用于在本地构建过滤结构。
 * 无需登录（AuthInterceptor 白名单前缀 "system/sensitive_word" 跳过认证）。
 * 若服务端词库为空（源不可达降级），返回空 words + 当前 version（空串），客户端自行决定行为。
 *
 * @param sensitiveWordService 敏感词服务，提供当前词表与版本号
 */
class SensitiveWordDownloadHandler(
    private val sensitiveWordService: SensitiveWordService
) : Handler<SensitiveWordDownloadReq, SensitiveWordDownloadResp> {

    /** method 路由：system/sensitive_word_download */
    override val method: String = "system/sensitive_word_download"

    override suspend fun handle(req: SensitiveWordDownloadReq): SensitiveWordDownloadResp {
        val limit = if (req.limit <= 0) DEFAULT_LIMIT else req.limit.coerceAtMost(MAX_LIMIT)
        val offset = req.offset.coerceAtLeast(0)

        val words = sensitiveWordService.currentWords()
        val page = if (offset < words.size) words.subList(offset, (offset + limit).coerceAtMost(words.size)) else emptyList()
        val hasMore = offset + page.size < words.size

        return SensitiveWordDownloadResp.newBuilder()
            .addAllWords(page)
            .setTotal(words.size)
            .setVersion(sensitiveWordService.version)
            .setHasMore(hasMore)
            .build()
    }

    companion object {
        /** 默认每页条数 */
        private const val DEFAULT_LIMIT = 1000

        /** 单页上限，避免单条响应过大触发 gRPC 消息大小限制 */
        private const val MAX_LIMIT = 5000
    }
}
