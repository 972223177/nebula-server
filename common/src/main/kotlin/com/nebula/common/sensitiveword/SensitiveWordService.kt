package com.nebula.common.sensitiveword

/**
 * 敏感词服务抽象层（接口隔离，D-106 / D-120）。
 *
 * 定义上层（发送脱敏、下载、重载 Handler、启动初始化）所需的全部能力契约；
 * 具体匹配引擎（当前为 houbb/sensitive-word 的 DFA 实现，见 [HoubbSensitiveWordService]）
 * 被屏蔽在本接口之后，上层只依赖本抽象，不感知三方库。
 * 若未来替换匹配内核，只需新增一个实现并在 Koin 中换绑，调用方零改动。
 *
 * 下列性质由实现类保证（与具体引擎无关）：
 * - 词库以不可变快照存于内存，读操作（contains/filter/currentWords）无锁并发安全
 * - 重载时整体替换词库，对在线请求零停顿
 * - 启动先加载内嵌资源基线（不依赖网络），若配置可达远程源则尝试覆盖
 * - 源不可达或为空时按策略降级：启动时空词库/保留基线，reload 报错保留旧词库
 * - 脱敏不阻断消息投递（命中即替换为 `*`，见实现类 D-119 注释）
 */
interface SensitiveWordService {

    /** 当前词库版本（内容哈希），客户端据此判断是否需重新拉取 */
    val version: String

    /** 当前词库词数 */
    val wordCount: Int

    /** 词库是否为空 */
    val isEmpty: Boolean

    /** 当前词表（供客户端分页下载） */
    fun currentWords(): List<String>

    /** 文本是否包含敏感词（线程安全） */
    fun contains(text: String): Boolean

    /**
     * 将文本敏感词替换为掩码（线程安全，脱敏不阻断投递，命中即替换为 `*`）。
     *
     * @param text 待过滤文本
     * @return 过滤后的文本（未命中则原样返回）
     */
    fun filter(text: String): String

    /**
     * 启动加载：先加载内嵌资源基线，再尝试远程覆盖（若启用）。
     *
     * @return true 表示最终词库非空（加载/覆盖成功），false 表示内嵌资源与远程均为空
     */
    suspend fun loadAtStartup(): Boolean

    /**
     * 手动重载：优先远程（若启用），否则重读内嵌资源。
     *
     * @return true 表示重载成功，false 表示源不可达或为空（调用方应转为错误响应，且保留旧词库）
     */
    suspend fun reload(): Boolean
}
