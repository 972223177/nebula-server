package com.nebula.gateway.delivery

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * Delivery（消息投递）相关 Handler 收集器。
 *
 * 当前为预留扩展点，registerAll() 为空。
 * 后续 Phase 可在此注册新的交付相关 Handler。
 *
 * 实现说明：
 * - 实现 [HandlerCollector] 接口，通过 Koin [getAll] 自动发现
 * - 注册为 `single<HandlerCollector> { DeliveryHandlerCollector() }`
 */
class DeliveryHandlerCollector : HandlerCollector {

    /**
     * 当前无专属于 delivery 空间的 Handler（DeliveryAckHandler 注册在 chatHandlerCollector 中）。
     *
     * @param registry HandlerRegistry 实例
     */
    override fun registerAll(registry: HandlerRegistry) {
        // 预留扩展点
    }
}
