package com.nebula.common.log

import org.slf4j.Marker
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
    /** 登录审计 Marker，用于区分登录事件日志。
     *  显式声明非空 Marker（!! 强转）：SLF4J MarkerFactory.getMarker() 不会返回 null
     *  （name 为空时抛 IllegalArgumentException），保持非空避免下游 AuditMarkersTest 等
     *  直接调用 .name 时需要 ?. 处理；KLogger.info(Marker?) 仍可接收非空 Marker。 */
    val LOGIN: Marker = MarkerFactory.getMarker("AUDIT_LOGIN")!!
}
