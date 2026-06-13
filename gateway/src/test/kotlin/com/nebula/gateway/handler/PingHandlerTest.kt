package com.nebula.gateway.handler

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * PingHandler 应用层心跳 Handler 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景（Claude's Discretion: 心跳 Handler 单元测试）：
 * - handle() 返回 code=200 msg="pong" 的 Response
 * - method 值为 "system/ping"
 */
class PingHandlerTest {

    private val handler = PingHandler()

    @Test
    fun pingShouldReturnPongResponse() = runTest {
        val request = Request.getDefaultInstance()
        val response = handler.handle(request)

        assertEquals(BizCode.OK.code, response.code)
        assertEquals("pong", response.msg)
        assertEquals("system/ping", response.method)
    }

    @Test
    fun methodShouldBeSystemPing() {
        assertEquals("system/ping", handler.method)
    }
}
