package com.nebula.common.log

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [AuditMarkers] 的单元测试。
 *
 * 测试策略：
 * - 验证 LOGIN Marker 非空，确保 MarkerFactory 成功创建
 * - 验证 Marker 名称为 "AUDIT_LOGIN"，与 Logback 配置的 OnMarkerEvaluator 匹配
 * - 验证多次访问返回同一实例（单例语义）
 */
class AuditMarkersTest {

    // ─── LOGIN Marker 基本属性 ────────────────────────────────────────────────────

    @Test
    fun loginIsNotNull() {
        assertNotNull(AuditMarkers.LOGIN)
    }

    @Test
    fun loginGetNameReturnsAuditLogin() {
        assertEquals("AUDIT_LOGIN", AuditMarkers.LOGIN.name)
    }

    // ─── 单例语义 ────────────────────────────────────────────────────────────────

    @Test
    fun loginReturnsSameInstance() {
        val marker1 = AuditMarkers.LOGIN
        val marker2 = AuditMarkers.LOGIN
        assertEquals(marker1, marker2)
    }
}
