package com.nebula.common.sensitiveword

import com.nebula.common.sensitiveword.SensitiveWordNormalizer.NormalizedText
import com.nebula.common.sensitiveword.SensitiveWordNormalizer.normalize
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * 敏感词服务 — 内存中维护一份敏感词库快照，提供加载、重载与检测能力（D-113）。
 *
 * 设计要点：
 * - 词库以不可变 [Snapshot] 形式存于 [AtomicReference]，读操作（contains/filter/currentWords）无锁并发安全
 * - 加载/重载时构建新的 Aho-Corasick 匹配器，构建完成后整体 CAS 替换，对在线请求零停顿
 * - 匹配前对输入与词库统一做 [normalize] 归一化（大小写/全角/剔除分隔符与零宽字符），
 *   规避常见变形绕过；[filter] 的掩码区间由规范形映射回原串，避免打码错位（M1 review）
 * - 启动策略：先加载内嵌资源基线（始终可用、不依赖网络），若配置了可达的远程源则尝试覆盖
 * - 源不可达或返回为空时按策略降级：启动时空词库/保留基线，reload 报错且保留旧词库
 * - 不落本地磁盘，词库仅存在于内存（内嵌资源随 jar 打包，非运行时下载）
 *
 * @property fetcher 远程拉取器（接口隔离，未配置远程时为 [NoopWordFetcher]）
 * @property resourcePath 内嵌基线词库在 classpath 下的资源路径
 * @property remoteEnabled 是否启用远程源覆盖（source-url 非空）
 */
class SensitiveWordService(
    private val fetcher: SensitiveWordFetcher,
    private val resourcePath: String,
    private val remoteEnabled: Boolean
) {

    /** 不可变词库快照：词表 + 匹配器 + 版本号 */
    private data class Snapshot(
        val words: List<String>,
        val matcher: AhoCorasick,
        val version: String
    )

    /** 当前生效的词库快照（原子引用，读无锁） */
    private val ref = AtomicReference(Snapshot(emptyList(), AhoCorasick(emptyList()), ""))

    /** 当前词库词数 */
    val wordCount: Int get() = ref.get().words.size

    /** 当前词库版本（内容哈希），客户端据此判断是否需重新拉取 */
    val version: String get() = ref.get().version

    /** 词库是否为空 */
    val isEmpty: Boolean get() = ref.get().words.isEmpty()

    /** 当前词表（供客户端分页下载） */
    fun currentWords(): List<String> = ref.get().words

    /** 文本是否包含敏感词（线程安全，匹配前先做归一化） */
    fun contains(text: String): Boolean {
        if (text.isEmpty()) return false
        return ref.get().matcher.contains(normalize(text).text)
    }

    /**
     * 将文本敏感词替换为掩码（线程安全，匹配前先做归一化）。
     *
     * 命中区间在规范形上计算，再经 [NormalizedText.srcIndex] 映射回原串，
     * 使掩码覆盖原始字符（含被剔除的分隔符/零宽字符），避免打码错位。
     *
     * @param text 待过滤文本
     * @param mask 掩码字符，默认 `*`
     * @return 过滤后的文本（未命中则原样返回）
     */
    fun filter(text: String, mask: Char = '*'): String {
        if (text.isEmpty()) return text
        val n = normalize(text)
        if (n.text.isEmpty()) return text
        val normSpans = ref.get().matcher.findSpans(n.text)
        if (normSpans.isEmpty()) return text
        // 规范形区间 → 原串区间（左闭右开），并合并重叠
        val original = mergeIntervals(normSpans.map { span ->
            n.srcIndex[span.first] until (n.srcIndex[span.last] + 1)
        })
        val masked = BooleanArray(text.length)
        for (iv in original) for (j in iv) if (j in masked.indices) masked[j] = true
        return buildString(text.length) {
            for (i in text.indices) append(if (masked[i]) mask else text[i])
        }
    }

    /** 合并重叠/相邻区间并排序 */
    private fun mergeIntervals(raw: List<IntRange>): List<IntRange> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var cur = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.first <= cur.last + 1) {
                cur = cur.first until maxOf(cur.last, next.last)
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    /**
     * 启动加载：先加载内嵌资源基线，再尝试远程覆盖（若启用）。
     *
     * @return true 表示最终词库非空（加载/覆盖成功），false 表示内嵌资源与远程均为空
     */
    suspend fun loadAtStartup(): Boolean {
        val base = loadFromResource()
        if (base) {
            logger.info { "敏感词内嵌基线加载成功: 词数=${wordCount} version=$version" }
        } else {
            logger.warn { "未找到内嵌词库资源 $resourcePath 或解析为空，以空词库启动" }
        }
        // 复用同一次远程拉取结果，避免下方 return 短路失败时重复发起 HTTP 请求
        val remote = if (remoteEnabled) loadFromSource() else false
        if (remoteEnabled) {
            if (remote) {
                logger.info { "远程词库覆盖成功: 词数=${wordCount} version=$version" }
            } else {
                logger.warn { "远程词库拉取失败，保留内嵌基线（词数=${wordCount}）" }
            }
        }
        return base || remote
    }

    /**
     * 从内嵌资源文件加载基线词库（始终可用，不依赖网络）。
     *
     * @return true 表示加载成功（词数 > 0），false 表示资源缺失或解析为空
     */
    fun loadFromResource(): Boolean {
        val raw = readResource(resourcePath) ?: return false
        val words = parseWords(raw)
        if (words.isEmpty()) return false
        swap(words)
        return true
    }

    /**
     * 从远程源拉取词库（仅当 [remoteEnabled] 时有效）。
     *
     * @return true 表示拉取并加载成功，false 表示未启用远程、源不可达或为空
     */
    suspend fun loadFromSource(): Boolean {
        if (!remoteEnabled) return false
        val raw = try {
            fetcher.fetch()
        } catch (e: Exception) {
            logger.warn { "敏感词远程拉取失败，保留现有词库: ${e.message}" }
            return false
        }
        val words = parseWords(raw)
        if (words.isEmpty()) {
            logger.warn { "敏感词远程拉取成功但解析后为空，保留现有词库" }
            return false
        }
        swap(words)
        return true
    }

    /**
     * 手动重载：优先远程（若启用），否则重读内嵌资源（便于修改文件后热更新）。
     *
     * @return true 表示重载成功，false 表示源不可达或为空（调用方应转为错误响应，且保留旧词库）
     */
    suspend fun reload(): Boolean {
        return if (remoteEnabled) loadFromSource() else loadFromResource()
    }

    /**
     * 用给定词表整体替换内存词库（构建新匹配器后原子替换）。
     *
     * @param words 敏感词列表
     */
    fun swap(words: List<String>) {
        val version = computeVersion(words)
        // 词库同样做归一化后建自动机（与输入归一化保持一致），过滤重复与变形同义词
        val normalized = words.map { normalize(it).text }.filter { it.isNotEmpty() }
        val dedup = LinkedHashSet(normalized)
        ref.set(Snapshot(words, AhoCorasick(dedup), version))
        logger.info { "敏感词库已更新: 词数=${words.size} 规范形=${dedup.size} version=$version" }
    }

    /**
     * 读取 classpath 下的内嵌词库资源。
     *
     * @param path 资源路径
     * @return 资源文本；不存在返回 null
     */
    private fun readResource(path: String): String? {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * 解析词库原始文本为一行一词的词表。
     *
     * 处理规则：按行分割、去首尾空白、跳过空行与 `#` 开头的注释行、保序去重。
     *
     * @param raw 词库原始文本
     * @return 规范化后的词表
     */
    private fun parseWords(raw: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (line in raw.lineSequence()) {
            val w = line.trim()
            if (w.isEmpty() || w.startsWith("#")) continue
            seen.add(w)
        }
        return seen.toList()
    }

    /**
     * 计算词库内容哈希作为版本号（SHA-256 前 16 位十六进制）。
     *
     * 同一份词库内容在不同进程/重启间哈希稳定，客户端可据此精确判断是否发生变化。
     *
     * @param words 词表
     * @return 版本号字符串
     */
    private fun computeVersion(words: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (w in words) digest.update(w.toByteArray(Charsets.UTF_8))
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
