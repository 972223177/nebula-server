package com.nebula.common.sensitiveword

/**
 * 敏感词匹配前的文本归一化器（D-108）。
 *
 * 设计目的：消除常见的规避变形，使 [AhoCorasick] 在「规范形」上匹配，避免绕过。
 *
 * 归一化规则（基础层）：
 * - 全角 → 半角（ASCII 可见区 U+FF01..U+FF5E 整体偏移 -0xFEE0；全角空格 U+3000 → 半角空格）
 * - 大写 → 小写（仅 ASCII，规避 `Fuck`/`FUCK` 等大小写变形）
 * - 剔除所有非字母数字字符（空格、标点、`*`、斜杠、以及零宽字符 U+200B/U+200C/U+200D/U+FEFF 等），
 *   使「插入分隔符」类规避（`傻 逼`、`傻*逼`、`f.u.c.k`）被合并为连续词，与词库命中
 *
 * 已知残余风险（基础层取舍，非合规级保证，已在 review 中记录）：
 * - Unicode 等价变形（如数学粗体 `𝐟𝐮𝐜𝐤`）：`Character.isLetterOrDigit` 仍判为字母而保留，
 *   NFKC 亦不将其折叠为 ASCII，故不在基础层处理
 *
 * [normalize] 同时返回规范形到原串的下标映射，供过滤打码将掩码区间映射回原始文本（避免打码错位）。
 */
object SensitiveWordNormalizer {

    /** 归一化结果：规范形文本 + 字符下标映射（normIndex[i] = 原串中第 i 个规范字符的下标） */
    data class NormalizedText(val text: String, val srcIndex: IntArray)

    /** 仅保留字母与数字，其余（空格/标点/零宽等）一律视为噪声剔除 */
    private fun isKept(ch: Char): Boolean = ch.isLetterOrDigit()

    /** 全角 → 半角（仅 ASCII 可见区与全角空格），其余字符原样返回 */
    private fun toHalfWidth(ch: Char): Char {
        val code = ch.code
        return when {
            ch == '　' -> ' '               // 全角空格 U+3000
            code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
            else -> ch
        }
    }

    /**
     * 将文本归一到规范形，并保留规范形到原串的下标映射。
     *
     * @param input 原始文本
     * @return [NormalizedText]，[NormalizedText.text] 为规范形，[NormalizedText.srcIndex] 长度等于规范形长度
     */
    fun normalize(input: String): NormalizedText {
        if (input.isEmpty()) return NormalizedText("", IntArray(0))
        val sb = StringBuilder(input.length)
        val map = IntArray(input.length) // 最坏情况：全部字符保留
        var k = 0
        for (i in input.indices) {
            val folded = toHalfWidth(input[i])
            if (!isKept(folded)) continue
            sb.append(folded.lowercaseChar())
            map[k++] = i
        }
        return NormalizedText(sb.toString(), map.copyOf(k))
    }
}
