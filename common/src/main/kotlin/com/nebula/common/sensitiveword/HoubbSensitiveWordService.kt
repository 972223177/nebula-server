package com.nebula.common.sensitiveword

import com.github.houbb.sensitive.word.api.ISensitiveWordCharIgnore
import com.github.houbb.sensitive.word.api.IWordDeny
import com.github.houbb.sensitive.word.api.context.InnerSensitiveWordContext
import com.github.houbb.sensitive.word.bs.SensitiveWordBs
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * 敏感词服务 houbb/sensitive-word 实现（D-108 / D-109 / D-119 内核落地）。
 *
 * 设计：
 * - 实现 [SensitiveWordService] 抽象层，对上层屏蔽匹配引擎，仅本类依赖三方库 houbb（接口隔离，D-120）
 * - 词库以不可变 houbb 实例存于 [engineRef]，读操作（contains/filter/currentWords）无锁并发安全
 * - 加载/重载时构建新 houbb 实例（DFA 重建），构建完成后整体 CAS 替换，对在线请求零停顿（D-113）
 * - 归一化与绕过防御交给 houbb 内置 ignore 策略 + 自定义 charIgnore（D-108）：
 *   启用大小写/全角半角/繁简/英文异体/数字异体/重复忽略，并叠加自定义 charIgnore 忽略所有
 *   非字母数字字符（空格、分隔符、零宽字符），覆盖 `FUCK`、`ｆｕｃｋ`、`f u c k`、`f.u.c.k`、
 *   `傻@冒`、`敏#!@感$!@词` 等变形（较自研归一化覆盖更全）
 * - 脱敏由 houbb `replace` 直接在原串上打码，保留分隔符位置，命中即替换不阻断投递（D-119）
 * - 启动先加载内嵌资源基线（不依赖网络），若配置可达远程源则尝试覆盖（D-107）
 * - 源不可达或为空时按策略降级：启动时空词库/保留基线，reload 报错保留旧词库
 *
 * @property fetcher 远程拉取器（接口隔离，未配置远程时为 [NoopWordFetcher]）
 * @property resourcePath 内嵌基线词库在 classpath 下的资源路径
 * @property remoteEnabled 是否启用远程源覆盖（source-url 非空）
 */
class HoubbSensitiveWordService(
    private val fetcher: SensitiveWordFetcher,
    private val resourcePath: String,
    private val remoteEnabled: Boolean
) : SensitiveWordService {

    /** 当前生效的匹配引擎（houbb 实例，整体 CAS 替换，对在线请求零停顿） */
    private val engineRef = AtomicReference(buildEngine(emptyList()))

    /** 当前词库元信息（词表/版本/词数），与引擎同步替换 */
    private val metaRef = AtomicReference(Meta(emptyList(), "", 0))

    /** 当前词库版本（内容哈希），客户端据此判断是否需重新拉取 */
    override val version: String get() = metaRef.get().version

    /** 当前词库词数 */
    override val wordCount: Int get() = metaRef.get().wordCount

    /** 词库是否为空 */
    override val isEmpty: Boolean get() = metaRef.get().words.isEmpty()

    /** 当前词表（供客户端分页下载） */
    override fun currentWords(): List<String> = metaRef.get().words

    /** 文本是否包含敏感词（线程安全，houbb 内部完成归一化与 charIgnore） */
    override fun contains(text: String): Boolean {
        if (text.isEmpty()) return false
        return engineRef.get().contains(text)
    }

    /**
     * 将文本敏感词替换为掩码（线程安全，脱敏不阻断投递，D-119）。
     *
     * @param text 待过滤文本
     * @param mask 掩码字符，默认 `*`
     * @return 过滤后的文本（未命中则原样返回）
     */
    override fun filter(text: String): String {
        if (text.isEmpty()) return text
        // houbb 默认掩码为 '*'，在原串上仅替换命中字符、保留分隔符位置；命中即替换不阻断投递（D-119）
        return engineRef.get().replace(text)
    }

    /**
     * 启动加载：先加载内嵌资源基线，再尝试远程覆盖（若启用）。
     *
     * @return true 表示最终词库非空（加载/覆盖成功），false 表示内嵌资源与远程均为空
     */
    override suspend fun loadAtStartup(): Boolean {
        val base = loadFromResource()
        if (base) {
            logger.info { "敏感词内嵌基线加载成功: 词数=$wordCount version=$version" }
        } else {
            logger.warn { "未找到内嵌词库资源 $resourcePath 或解析为空，以空词库启动" }
        }
        // 复用同一次远程拉取结果，避免下方 return 短路失败时重复发起 HTTP 请求
        val remote = if (remoteEnabled) loadFromSource() else false
        if (remoteEnabled) {
            if (remote) {
                logger.info { "远程词库覆盖成功: 词数=$wordCount version=$version" }
            } else {
                logger.warn { "远程词库拉取失败，保留内嵌基线（词数=$wordCount）" }
            }
        }
        return base || remote
    }

    /**
     * 手动重载：优先远程（若启用），否则重读内嵌资源（便于修改文件后热更新）。
     *
     * @return true 表示重载成功，false 表示源不可达或为空（保留旧词库）
     */
    override suspend fun reload(): Boolean {
        return if (remoteEnabled) loadFromSource() else loadFromResource()
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
     * 用给定词表整体替换内存词库（构建新 houbb 实例后原子替换）。
     *
     * @param words 敏感词列表
     */
    fun swap(words: List<String>) {
        val version = computeVersion(words)
        engineRef.set(buildEngine(words))
        metaRef.set(Meta(words, version, words.size))
        logger.info { "敏感词库已更新: 词数=${words.size} version=$version" }
    }

    /**
     * 构建 houbb 匹配引擎：启用内置 ignore 策略（大小写/全角/繁简/英文异体/数字异体/重复），
     * 并叠加自定义 charIgnore 忽略所有非字母数字字符（空格/分隔符/零宽），覆盖插入式变形绕过。
     *
     * @param words 敏感词列表
     * @return 构建完成的 houbb 实例（不可变，可安全并发读取）
     */
    private fun buildEngine(words: List<String>): SensitiveWordBs {
        val deny = IWordDeny { words }
        return SensitiveWordBs.newInstance()
            .ignoreCase(true)
            .ignoreWidth(true)
            .ignoreChineseStyle(true)
            .ignoreEnglishStyle(true)
            .ignoreNumStyle(true)
            .ignoreRepeat(true)
            .charIgnore(SKIP_CHARS_IGNORE)
            .wordDeny(deny)
            .init()
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
     * 计算词库内容哈希作为版本号（SHA-256 前 8 位十六进制）。
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

    /** 词库元信息快照（与引擎同步 CAS 替换） */
    private data class Meta(val words: List<String>, val version: String, val wordCount: Int)

    companion object {
        /**
         * 自定义字符忽略策略（D-108）：忽略所有非字母数字字符（空格/分隔符/零宽等），
         * 使「插入分隔符/零宽」类规避（`f u c k`、`傻@冒`）被合并为连续词后命中。
         * 中文与字母数字均保留，不影响正常匹配。
         */
        private val SKIP_CHARS_IGNORE: ISensitiveWordCharIgnore = object : ISensitiveWordCharIgnore {
            override fun ignore(index: Int, text: String, context: InnerSensitiveWordContext): Boolean =
                !text[index].isLetterOrDigit()
        }

        private val logger = KotlinLogging.logger {}
    }
}
