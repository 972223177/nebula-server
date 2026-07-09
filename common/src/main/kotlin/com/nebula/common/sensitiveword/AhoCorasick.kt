package com.nebula.common.sensitiveword

/**
 * Aho-Corasick 多模式匹配器 — 一次扫描即可检出文本中出现的所有敏感词（D-109）。
 *
 * 设计要点：
 * - 构建阶段（[build]）将词表组织为 Trie + 失配指针（fail link），为不可变快照
 * - 匹配阶段（[contains]/[filter]）只读遍历，无锁、线程安全，可并发服务于多个请求
 * - 重载时由 [SensitiveWordService] 整体替换为新实例，旧实例在无引用后自然回收
 *
 * 内存占用：节点数 ≈ 词数 × 平均词长，每个节点一个 `MutableMap<Char, Int>` 子节点表。
 * 对于数万级中文词库（约数十 MB）完全可控，且构建仅在加载/重载时发生一次。
 *
 * @property keywords 敏感词集合（可含重复，内部去重）
 */
class AhoCorasick(keywords: Collection<String>) {

    /** Trie 节点：子节点转移表、失配指针、该节点结束的敏感词集合 */
    private data class Node(
        val next: MutableMap<Char, Int> = mutableMapOf(),
        var fail: Int = 0,
        val out: MutableList<String> = mutableListOf()
    )

    /** 节点表，下标 0 为根节点 */
    private val nodes: List<Node>

    init {
        val builder = mutableListOf(Node()) // 根节点 index=0
        // 1. 构建 Trie
        for (kw in keywords) {
            if (kw.isEmpty()) continue
            var cur = 0
            for (ch in kw) {
                cur = builder[cur].next.getOrPut(ch) { builder.add(Node()); builder.lastIndex }
            }
            if (kw !in builder[cur].out) builder[cur].out.add(kw)
        }
        // 2. BFS 计算失配指针并合并输出
        val queue = ArrayDeque<Int>()
        for ((_, idx) in builder[0].next) {
            builder[idx].fail = 0
            queue.add(idx)
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for ((ch, idx) in builder[u].next) {
                var f = builder[u].fail
                while (f != 0 && !builder[f].next.containsKey(ch)) f = builder[f].fail
                builder[idx].fail = builder[f].next[ch] ?: 0
                builder[idx].out.addAll(builder[builder[idx].fail].out)
                queue.add(idx)
            }
        }
        nodes = builder
    }

    /**
     * 扫描文本，返回所有命中敏感词的字符区间（左闭右开）。
     *
     * @param text 待检测文本
     * @return 命中区间列表，每个区间为 [start, end)
     */
    fun findSpans(text: String): List<IntRange> {
        if (text.isEmpty() || nodes.size <= 1) return emptyList()
        val spans = mutableListOf<IntRange>()
        var state = 0
        for (i in text.indices) {
            val ch = text[i]
            while (state != 0 && !nodes[state].next.containsKey(ch)) state = nodes[state].fail
            state = nodes[state].next[ch] ?: 0
            if (nodes[state].out.isNotEmpty()) {
                for (kw in nodes[state].out) {
                    val start = i - kw.length + 1
                    if (start >= 0) spans.add(start..i)
                }
            }
        }
        return spans
    }

    /** 文本是否包含任意敏感词 */
    fun contains(text: String): Boolean = findSpans(text).isNotEmpty()

    /**
     * 将文本中所有敏感词替换为掩码字符（默认 `*`，连续命中合并为一段掩码）。
     *
     * @param text 待过滤文本
     * @param mask 掩码字符，默认 `*`
     * @return 过滤后的文本（未命中则原样返回）
     */
    fun filter(text: String, mask: Char = '*'): String {
        val spans = findSpans(text)
        if (spans.isEmpty()) return text
        val masked = BooleanArray(text.length)
        for (range in spans) {
            for (j in range) if (j in masked.indices) masked[j] = true
        }
        return buildString(text.length) {
            for (i in text.indices) append(if (masked[i]) mask else text[i])
        }
    }
}
