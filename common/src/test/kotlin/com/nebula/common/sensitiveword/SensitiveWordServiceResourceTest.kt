package com.nebula.common.sensitiveword

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证敏感词服务从内嵌资源文件加载基线词库的能力。
 */
class SensitiveWordServiceResourceTest {

    /** 内嵌资源基线应成功加载，contains 能命中种子词，空输入返回 false */
    @Test
    fun loadFromResourcePopulatesWordsAndMatches() = runTest {
        val service = HoubbSensitiveWordService(NoopWordFetcher(), "sensitive/builtin-words.txt", false)
        val ok = service.loadFromResource()
        assertTrue(ok, "内嵌资源应加载成功")
        assertTrue(service.wordCount > 0, "词数应大于 0")
        assertTrue(service.contains("你是个傻逼"), "应命中种子词")
        assertFalse(service.contains("你好世界"), "正常文本不应命中")
    }

    /** 未配置远程源时 reload 应重读内嵌资源并成功，不发起网络请求 */
    @Test
    fun reloadWithoutRemoteReReadsResource() = runTest {
        val service = HoubbSensitiveWordService(NoopWordFetcher(), "sensitive/builtin-words.txt", false)
        val ok = service.reload()
        assertTrue(ok)
        assertTrue(service.contains("fuck"))
    }

    /** 资源路径不存在时 loadFromResource 返回 false（降级为空词库） */
    @Test
    fun missingResourceReturnsFalse() {
        val service = HoubbSensitiveWordService(NoopWordFetcher(), "sensitive/does-not-exist.txt", false)
        assertFalse(service.loadFromResource())
    }
}
