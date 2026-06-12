package com.nebula.gateway.handler.chat.send

/**
 * Step 链接口 — 将 chat/send 拆分为独立可测试的单元（D-13）。
 *
 * 每个 Step 负责单一职责，通过 [SendContext] 传递共享状态。
 * 执行顺序：ValidateStep → DedupStep → WriteStep
 * （推送和未读计数在 SendMessageHandler 中异步 fire-and-forget 执行）
 *
 * Step 链终止策略：
 * - return false：正常终止（当前未使用，保留扩展性）
 * - throw SendMessageException：错误终止，由 ExceptionInterceptor 捕获
 */
interface SendMessageStep {

    /**
     * 执行 Step 逻辑。
     *
     * @param context Step 链共享上下文，包含请求、发送者信息和逐步填充的处理结果
     * @return true 继续执行下一步，false 终止链
     */
    suspend fun execute(context: SendContext): Boolean
}
