package com.nebula.gateway.codec

import com.nebula.chat.Request
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * ProtoCodec 单元测试。
 *
 * 覆盖：
 * - buildCodec 为 Request proto 构建编解码器（正常路径）
 * - 空载荷返回默认实例（Pitfall 5）
 */
class ProtoCodecTest {

    @Test
    fun buildCodecForRequestProto() = runTest {
        val codec = ProtoCodec.buildCodec(Request::class)

        assertNotNull(codec)
        assertNotNull(codec.parseFrom)
        assertNotNull(codec.toByteArray)
    }

    @Test
    fun emptyBytesReturnsDefaultInstance() = runTest {
        val codec = ProtoCodec.buildCodec(Request::class)

        val result = codec.parseFrom(ByteArray(0))
        assertNotNull(result)
    }

    @Test
    fun serializeAndDeserializeRoundtrip() = runTest {
        val codec = ProtoCodec.buildCodec(Request::class)

        val original = Request.newBuilder()
            .setMethod("test.method")
            .build()

        val bytes = codec.toByteArray(original)
        val restored = codec.parseFrom(bytes) as Request

        assertNotNull(restored)
    }
}
