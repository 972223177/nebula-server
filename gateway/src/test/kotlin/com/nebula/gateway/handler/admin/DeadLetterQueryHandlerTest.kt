package com.nebula.gateway.handler.admin

import com.nebula.chat.admin.DeadLetterQueryReq
import com.nebula.chat.admin.DeadLetterQueryResp
import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.service.admin.DeadLetterService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * DeadLetterQueryHandler 单元测试（Phase 10）。
 *
 * 覆盖场景：
 * - 正常分页查询返回结果及字段映射
 * - 页码 < 1 时自动调整为 1
 * - 每页大小超出范围时自动限制（1 ~ 100）
 * - 按状态过滤查询
 * - 空状态字符串自动转换为 null（查询全部）
 * - 实体中 nullable 字段为 null 时 Proto 默认值为 0
 */
class DeadLetterQueryHandlerTest {

    private lateinit var deadLetterService: DeadLetterService
    private lateinit var handler: DeadLetterQueryHandler

    /** 通用的测试实体，字段值用于断言检查 */
    private val testEntity: DeadLetterEntity by lazy {
        DeadLetterEntity(
            conversationId = "conv-001",
            senderUid = 1L,
            messageType = 1,
            content = "测试消息内容",
            clientTs = 1000L,
            failReason = "超时",
            failCount = 3,
            status = "pending"
        ).apply {
            id = 42L
            msgId = 100L
            createdAt = LocalDateTime.of(2024, 1, 15, 10, 30, 0)
        }
    }

    @BeforeEach
    fun setUp() {
        deadLetterService = mockk<DeadLetterService>(relaxed = true)
        handler = DeadLetterQueryHandler(deadLetterService)
    }

    @Test
    fun handleShouldReturnPagedResults() = runTest {
        // 准备：分页数据包含一条死信记录
        val page = PageImpl(
            listOf(testEntity),
            PageRequest.of(0, 20),
            1L
        )
        coEvery { deadLetterService.query(any(), any(), any()) } returns page

        // 执行
        val req = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(20)
            .build()
        val resp = handler.handle(req)

        // 验证
        assertNotNull(resp, "响应不应为空")
        assertEquals(1, resp.itemsCount, "应返回 1 条死信记录")
        assertEquals(1, resp.total, "总数应为 1")
        val item = resp.getItems(0)
        assertEquals(42L, item.id, "死信 ID 应映射为 42")
        assertEquals(100L, item.msgId, "消息 ID 应映射为 100")
        assertEquals("conv-001", item.conversationId, "会话 ID 应映射正确")
        assertEquals(1L, item.senderUid, "发送者 UID 应映射正确")
        assertEquals("超时", item.failReason, "失败原因应映射正确")
        assertEquals(3, item.failCount, "失败次数应映射正确")
        assertEquals("pending", item.status, "状态应映射正确")
        assertEquals(1705314600000L, item.createdAt, "创建时间应正确转换为毫秒时间戳")
    }

    @Test
    fun handleShouldClampPageToMin1() = runTest {
        // 执行：页码传 0
        val req = DeadLetterQueryReq.newBuilder()
            .setPage(0)
            .setPageSize(20)
            .build()
        handler.handle(req)

        // 验证：page 被限制为 1
        coVerify { deadLetterService.query(1, 20, null) }
    }

    @Test
    fun handleShouldClampPageSizeToRange() = runTest {
        // 测试 pageSize = -1（应限制为 1）
        val reqSmall = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(-1)
            .build()
        handler.handle(reqSmall)
        coVerify { deadLetterService.query(1, 1, null) }

        clearMocks(deadLetterService, answers = false, recordedCalls = true)

        // 测试 pageSize = 200（应限制为 100）
        val reqLarge = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(200)
            .build()
        handler.handle(reqLarge)
        coVerify { deadLetterService.query(1, 100, null) }
    }

    @Test
    fun handleShouldFilterByStatus() = runTest {
        // 执行：传入状态过滤
        val req = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(20)
            .setStatus("pending")
            .build()
        handler.handle(req)

        // 验证：service.query 携带状态参数
        coVerify { deadLetterService.query(1, 20, "pending") }
    }

    @Test
    fun handleShouldConvertBlankStatusToNull() = runTest {
        // 执行：传入空字符串状态
        val req = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(20)
            .setStatus("")
            .build()
        handler.handle(req)

        // 验证：空字符串被转换为 null，查询全部
        coVerify { deadLetterService.query(1, 20, null) }
    }

    @Test
    fun handleShouldConvertEntityToProto() = runTest {
        // 准备：实体的所有 nullable 字段均为 null
        val nullEntity = DeadLetterEntity(
            conversationId = "conv-001",
            senderUid = 1L,
            messageType = 1,
            content = "test",
            clientTs = 1000L,
            failReason = "timeout",
            failCount = 3,
            status = "pending"
        )
        val page = PageImpl(
            listOf(nullEntity),
            PageRequest.of(0, 20),
            1L
        )
        coEvery { deadLetterService.query(any(), any(), any()) } returns page

        // 执行
        val req = DeadLetterQueryReq.newBuilder()
            .setPage(1)
            .setPageSize(20)
            .build()
        val resp = handler.handle(req)

        // 验证：nullable 字段为 null 时，Proto 默认值为 0
        assertEquals(0, resp.getItems(0).id, "id 为 null 时应默认为 0")
        assertEquals(0L, resp.getItems(0).msgId, "msgId 为 null 时应默认为 0")
        assertEquals(0L, resp.getItems(0).createdAt, "createdAt 为 null 时应默认为 0")
    }
}
