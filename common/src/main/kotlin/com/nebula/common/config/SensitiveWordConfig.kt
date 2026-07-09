package com.nebula.common.config

/**
 * 敏感词库配置。
 *
 * 词库策略（D-114）：
 * - 基线词库内嵌于项目资源文件（[resourcePath]），随 jar 打包，服务端启动时优先加载，
 *   不依赖外网，保证检测能力开箱即用
 * - [sourceUrl] 为可选远程源（一行一词的 raw 文本）。非空时启动后会尝试拉取并覆盖基线；
 *   留空则完全不发起网络请求，仅用内嵌词库
 *
 * @property sourceUrl 可选远程词库地址（一行一词纯文本）；为空表示仅用内嵌资源
 * @property resourcePath 内嵌基线词库在 classpath 下的资源路径
 */
data class SensitiveWordConfig(
    /** 可选远程词库地址；为空表示不拉取远程，仅用内嵌资源 */
    val sourceUrl: String = "",
    /** 内嵌基线词库资源路径（classpath 下） */
    val resourcePath: String = "sensitive/builtin-words.txt"
) {
    companion object {
        /**
         * 可选远程源默认值 — 留空，默认仅用内嵌词库。
         *
         * 注意：Gitee 等平台的 raw 文件对服务端 HTTP 客户端整体封锁（返回 Access denied），
         * 故不预设任何远程默认值。如需远程覆盖，请在 config 中显式配置一个服务端可达的
         * 一行一词 raw 文本地址，或通过环境变量 SENSITIVE_WORD_SOURCE_URL 注入。
         */
        const val DEFAULT_SOURCE_URL: String = ""
    }
}
