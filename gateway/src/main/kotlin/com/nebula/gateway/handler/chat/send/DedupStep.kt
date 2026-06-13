package com.nebula.gateway.handler.chat.send

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息去重 Step — 占位实现（D-07, D-13, D-72）。
 *
 * D-72: SETNX 去重已下沉到 MessageQueueRepository.enqueue() 上游的 checkAndSetDedup() 中，
 * 本 Step 保持为 no-op 占位，保留在 Step 链中以维持 SendMessageHandler 的 Step 链结构不变。
 *
 * @param connection 保留参数以维持构造签名向后兼容
 */
class DedupStep(
    @Suppress("UNUSED_PARAMETER") connection: Any? = null
) : SendMessageStep {

    /**
     * 空实现 — 去重已下沉到 MessageQueueRepository.checkAndSetDedup()（D-72）。
     *
     * @param context Step 链共享上下文
     * @return true 始终通过
     */
    override suspend fun execute(context: SendContext): Boolean {
        return true
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
