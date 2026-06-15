package com.nebula.common.log

import org.slf4j.MarkerFactory

/**
 * 审计日志 Marker 定义（CQ-10）。
 *
 * 使用 SLF4J Marker 将审计日志从通用业务日志中分离：
 * - [LOGIN] 标记登录成功/失败事件，配合 Logback OnMarkerEvaluator 写入独立的 audit.log
 *
 * 用法：
 * ```
 * logger.info(AuditMarkers.LOGIN) { "user_login | uid=1001 | method=password | success=true" }
 * ```
 */
object AuditMarkers {
    /** 登录审计 Marker，用于区分登录事件日志 */
    val LOGIN = MarkerFactory.getMarker("AUDIT_LOGIN")
}
