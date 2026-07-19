package com.nebula.common.sensitiveword

/**
 * 空实现拉取器 — 远程源（Gitee）已被封锁，始终使用此实现（D-112）。
 *
 * 保留接口与 NoopWordFetcher 作为占位，若未来更换可达远程源，可新增实现替换。
 * [fetch] 始终抛异常；配合 [HoubbSensitiveWordService] 的远程关闭策略，
 * 服务仅依赖内嵌资源词库，不会发起任何网络请求。
 */
class NoopWordFetcher : SensitiveWordFetcher {
    override suspend fun fetch(): String {
        throw IllegalStateException("未配置远程敏感词源（source-url 为空），不应发起远程拉取")
    }
}
