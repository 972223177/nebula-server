package com.nebula.gateway.handler

import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.session.Session
import kotlin.coroutines.CoroutineContext

/**
 * Session 在 CoroutineContext 中的 Key。
 *
 * AuthInterceptor 在认证通过后通过 `withContext(SessionKey(session))` 将 Session 注入协程上下文，
 * Handler 通过 `coroutineContext.requireSession()` 获取当前用户 Session。
 *
 * 实现 [CoroutineContext.Element] 以支持通过协程上下文传递 Session（D-03）。
 */
data class SessionKey(val session: Session) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<SessionKey>
}

/**
 * 从当前协程上下文获取 Session。
 *
 * 由 AuthInterceptor 在 `withContext(SessionKey(session))` 中注入。
 * 如果 Session 不存在（未通过认证），抛出 [BizException] 的 UNAUTHORIZED 类型。
 *
 * @return 当前协程上下文的 Session
 * @throws BizException(BizCode.UNAUTHORIZED) 若 Session 未注入
 */
suspend fun CoroutineContext.requireSession(): Session {
    return this[SessionKey]?.session ?: throw BizException(BizCode.UNAUTHORIZED)
}
