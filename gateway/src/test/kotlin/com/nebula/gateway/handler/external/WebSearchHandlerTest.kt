package com.nebula.gateway.handler.external

import com.nebula.chat.external.SearchRequest
import com.nebula.chat.external.SearchResponse
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.gateway.testutil.DEFAULT_SESSION
import com.nebula.gateway.testutil.withSession
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.WebSearchInvoker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * WebSearchHandler 单元测试（external-service-backend.md 阶段 4/5）。
 *
 * 覆盖场景：
 * - 正常委托：Handler 取登录态 userId 并委托 [ExternalServiceOrchestrator.webSearch]
 *   （query / maxResults / searchType 透传）
 * - method 路由值："external/web_search"
 * - 无 Session 上下文时抛 [BizException](UNAUTHORIZED)
 */
class WebSearchHandlerTest {

    private lateinit var orchestrator: ExternalServiceOrchestrator
    private lateinit var handler: WebSearchHandler

    @BeforeEach
    fun setup() {
        orchestrator = mockk()
        handler = WebSearchHandler(orchestrator)
    }

    @Test
    fun methodShouldBeExternalWebSearch() {
        assertEquals("external/web_search", handler.method)
    }

    @Test
    fun handleShouldDelegateToOrchestratorWithSessionUserId() = runTest {
        val userId = DEFAULT_SESSION.userId
        val resp = SearchResponse.newBuilder()
            .setFormatted("搜索结果（共 3 条）")
            .build()
        coEvery { orchestrator.invoke(WebSearchInvoker.SERVICE_ID, eq(userId), any(), any()) } returns resp

        val result = withSession(DEFAULT_SESSION) {
            handler.handle(
                SearchRequest.newBuilder()
                    .setQuery("kotlin coroutines")
                    .setMaxResults(5)
                    .setSearchType("search")
                    .build()
            )
        }

        assertEquals("搜索结果（共 3 条）", result.formatted)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = assertFailsWith<BizException> {
            handler.handle(
                SearchRequest.newBuilder().setQuery("kotlin").build()
            )
        }
        assertEquals(BizCode.UNAUTHORIZED, exception.bizCode)
    }
}
