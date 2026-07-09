package com.nebula.common.sensitiveword

/**
 * 空实现拉取器 — 当未配置远程源（[SensitiveWordConfig.sourceUrl] 为空）时使用（D-112）。
 *
 * 占位实现，[fetch] 始终抛异常；配合 [SensitiveWordService] 的「远程可选」策略，
 * 未配置远程源时服务仅依赖内嵌资源词库，不会发起任何网络请求。
 */
class NoopWordFetcher : SensitiveWordFetcher {
    override suspend fun fetch(): String {
        throw IllegalStateException("未配置远程敏感词源（source-url 为空），不应发起远程拉取")
    }
}
