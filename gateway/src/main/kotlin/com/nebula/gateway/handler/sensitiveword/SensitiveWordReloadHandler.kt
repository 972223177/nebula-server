package com.nebula.gateway.handler.sensitiveword

import com.nebula.chat.sensitiveword.SensitiveWordReloadReq
import com.nebula.chat.sensitiveword.SensitiveWordReloadResp
import com.nebula.chat.sensitiveword.SensitiveWordUpdatedPayload
import com.nebula.chat.PushEventType
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.push.PushService

/**
 * 敏感词库手动重载 Handler — method = "admin/sensitive_word_reload"（D-117）。
 *
 * 强制重新加载词库并重建内存匹配器：若配置了可达的远程源（source-url 非空）则从远程拉取，
 * 否则重读内嵌资源文件（便于直接编辑 builtin-words.txt 后热更新）。成功后向所有在线客户端 PUSH
 * SENSITIVE_WORD_UPDATED 通知，客户端收到后重新调用 download 接口。
 * 源不可达或返回为空时抛出 [BizException]（SENSITIVE_WORD_FETCH_FAILED），保留旧词库不替换。
 *
 * @property sensitiveWordService 敏感词服务，执行重载
 * @property pushService 推送服务，用于广播更新通知
 */
class SensitiveWordReloadHandler(
    private val sensitiveWordService: SensitiveWordService,
    private val pushService: PushService
) : Handler<SensitiveWordReloadReq, SensitiveWordReloadResp> {

    /** method 路由：admin/sensitive_word_reload（admin/ 前缀跳过认证，与其他 admin 接口一致） */
    override val method: String = "admin/sensitive_word_reload"

    override suspend fun handle(req: SensitiveWordReloadReq): SensitiveWordReloadResp {
        val ok = sensitiveWordService.reload()
        if (!ok) {
            // 源不可达/为空：返回错误，保留现有词库
            throw BizException(
                BizCode.SENSITIVE_WORD_FETCH_FAILED,
                "敏感词库重载失败：源不可达或返回为空，请检查 sensitive-word.source-url 配置或网络"
            )
        }

        // 重载成功，向所有在线客户端推送更新通知（客户端据此重新 download）
        val payload = SensitiveWordUpdatedPayload.newBuilder()
            .setVersion(sensitiveWordService.version)
            .setWordCount(sensitiveWordService.wordCount)
            .setUpdatedAt(System.currentTimeMillis())
            .build()
        pushService.pushToAll(PushEventType.SENSITIVE_WORD_UPDATED, payload.toByteString())

        return SensitiveWordReloadResp.newBuilder()
            .setSuccess(true)
            .setWordCount(sensitiveWordService.wordCount)
            .setVersion(sensitiveWordService.version)
            .setReloadedAt(System.currentTimeMillis())
            .build()
    }
}
