package com.nebula.common.sensitiveword

/**
 * 敏感词库拉取器接口 — 隔离具体 HTTP 实现，便于后续替换技术方案（D-110）。
 *
 * 当前 Gitee raw 源已被封锁，默认始终绑定 [NoopWordFetcher]；
 * 若未来改为其他源，只需新增一个实现并在 Koin 中替换绑定即可。
 *
 * 拉取结果为词库原始文本（约定一行一词），解析由 [SensitiveWordService] 负责。
 */
interface SensitiveWordFetcher {

    /**
     * 从源拉取词库原始文本。
     *
     * @return 词库原始文本（一行一词的纯文本）
     * @throws Exception 源不可达、HTTP 状态码非 200、响应为空等任何异常情况
     */
    suspend fun fetch(): String
}
