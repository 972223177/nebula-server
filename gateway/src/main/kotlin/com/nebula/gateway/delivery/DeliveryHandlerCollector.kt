package com.nebula.gateway.delivery

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * Delivery（消息投递）相关 Handler 收集器。
 *
 * 当前暂为占位实现，registerAll() 为空。
 * 10-04 将在此注册 DeliveryRecvHandler（处理客户端 DeliveryAck 上报请求）。
 *
 * 实现说明：
 * - 实现 [HandlerCollector] 接口，通过 Koin [getAll] 自动发现
 * - 注册为 `single<HandlerCollector> { DeliveryHandlerCollector() }`
 */
class DeliveryHandlerCollector : HandlerCollector {

    /**
     * 暂不注册任何 Handler，保留给 10-04 扩展。
     *
     * @param registry HandlerRegistry 实例
     */
    override fun registerAll(registry: HandlerRegistry) {
        // 空实现，10-04 将在此注册 DeliveryRecvHandler
    }
}
