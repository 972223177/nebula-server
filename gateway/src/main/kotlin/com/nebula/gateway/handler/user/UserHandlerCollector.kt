package com.nebula.gateway.handler.user

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.di.register

/**
 * 用户业务 Handler 收集器 — 注册用户模块的所有 Handler（Phase 5）。
 */
class UserHandlerCollector(
    private val loginHandler: LoginHandler,
    private val registerHandler: RegisterHandler,
    private val searchUserHandler: SearchUserHandler,
    private val getProfileHandler: GetProfileHandler,
    private val batchGetUserHandler: BatchGetUserHandler,
    private val batchGetStatusHandler: BatchGetStatusHandler,
    private val setPrivacyHandler: SetPrivacyHandler,
    private val getPrivacyHandler: GetPrivacyHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(loginHandler)
        registry.register(registerHandler)
        registry.register(searchUserHandler)
        registry.register(getProfileHandler)
        registry.register(batchGetUserHandler)
        registry.register(batchGetStatusHandler)
        registry.register(setPrivacyHandler)
        registry.register(getPrivacyHandler)
    }
}
