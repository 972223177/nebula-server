package com.nebula.gateway.di

import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Proto 约定守护测试（verifyProtoConventions 的 test 形态，与 [MethodNamesConsistencyTest] 同范式）。
 *
 * 固化 P2 proto 债清理成果：封闭状态/角色必须 enum 化，禁止用 string 表达。
 * 解析 `proto/src/main/proto` 下全部 .proto 源文件，校验：
 *
 * - 规则 A（强制）：message 作用域内 `string` 类型字段，其 snake_case 任一分段命中状态语义词
 *   （status/state/role/level/stage/phase/kind/category/type）即视为应使用 enum，
 *   除非该 `(message, field)` 命中 [ALLOWED_STRING_STATUS_FIELDS] 白名单（经评审接受的已知 string 字段）。
 *
 * 设计取舍：
 * - 解析基于 .proto 源文本（非 descriptor 反射），直接对应「守护源文件约定」且能给出 `文件:行号`。
 * - 枚举首值必须为 `*_UNKNOWN`/`*_UNSPECIFIED=0` 是推荐约定，但存量有 5 个枚举
 *   （ChatContentType / FriendApprovalMode / FriendRequestDirection / FriendRequestStatus / FriendRelationStatus）
 *   首值非哑值，且改名涉及客户端协同，故本测试不强制，仅在 CODEBUDDY.md 记录为推荐约定（待专项治理）。
 * - 新增 string 状态类字段时若确为自由文本（如 search_type/category），须在 [ALLOWED_STRING_STATUS_FIELDS]
 *   显式登记并经评审，避免护栏静默通过技术债。
 */
class ProtoConventionsTest {

    /** 状态/角色语义词：命中即要求该字段为 enum 而非 string */
    private val STATE_SEMANTIC_TERMS = setOf(
        "status", "state", "role", "level", "stage", "phase", "kind", "category", "type"
    )

    /** 经评审接受的已知 string 状态类字段（fully-qualified `Message.field`），新增须评审 */
    private val ALLOWED_STRING_STATUS_FIELDS = setOf(
        "SearchRequest.search_type", // 搜索类型，服务端白名单校验，当前为自由字符串
        "ServiceDescriptor.category", // 服务分类，自由字符串
        "ListServicesRequest.category" // 按分类过滤，自由字符串
    )

    private data class Violation(val file: String, val line: Int, val message: String)

    @Test
    fun protoFilesConformToConventions() {
        val protoDir = locateProtoDir()
        val violations = mutableListOf<Violation>()
        Files.walk(protoDir).use { stream ->
            stream
                .filter { it.toString().endsWith(".proto") }
                .sorted()
                .forEach { file -> violations += checkFile(file, protoDir) }
        }
        assertTrue(
            violations.isEmpty(),
            "Proto 约定违规（共 ${violations.size} 处）：\n" +
                violations.joinToString("\n") { "${it.file}:${it.line}  ${it.message}" }
        )
    }

    /**
     * 阳性测试：确认解析逻辑能识别违规 string 状态字段，且白名单字段被豁免。
     * 构造样本含 role/status（应判违规）与白名单内的 category（应豁免）→ 期望 2 处违规。
     */
    @Test
    fun detectsStringStateFieldAndRespectsAllowlist() {
        val sample = """
            message ServiceDescriptor {
              string category = 1;   // 白名单豁免
              string role = 2;       // 违规
              string status = 3;     // 违规
            }
        """.trimIndent()
        val violations = checkText("sample.proto", sample)
        assertEquals(2, violations.size, violations.joinToString("\n") { it.message })
    }

    private fun locateProtoDir(): Path {
        val candidates = listOf(
            Paths.get("proto", "src", "main", "proto"),
            Paths.get("..", "proto", "src", "main", "proto"),
            Paths.get(System.getProperty("user.dir"), "proto", "src", "main", "proto")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 proto 源目录，候选路径: $candidates")
    }

    private fun checkFile(file: Path, protoDir: Path): List<Violation> {
        val rel = protoDir.relativize(file).toString().replace('\\', '/')
        return checkText(rel, Files.readString(file))
    }

    /**
     * 解析单份 proto 文本，返回违规列表。
     * scopeStack 记录当前嵌套作用域（m=message / e=enum / o=oneof / s=service），
     * 字段归属最近的外层 message；`}` 与栈顶作用域配对弹出。
     */
    private fun checkText(fileName: String, text: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        val lines = text.lines()
        var inBlockComment = false
        val scopeStack = ArrayDeque<Pair<String, String>>() // (type, messageName?) 仅 message 带名

        lines.forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            var line = rawLine

            // 剥离块注释（支持跨行 /* ... */）
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end >= 0) {
                    line = line.substring(end + 2)
                    inBlockComment = false
                } else {
                    return@forEachIndexed
                }
            }
            var from = 0
            while (true) {
                val start = line.indexOf("/*", from)
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlockComment = true
                    break
                }
                line = line.removeRange(start, end + 2)
            }

            // 剥离行注释
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            line = line.trim()
            if (line.isEmpty()) return@forEachIndexed

            // 作用域进入
            when {
                line.startsWith("message ") && line.contains("{") -> {
                    val name = line.substring(8, line.indexOf("{")).trim()
                    scopeStack.addLast("m" to name)
                }
                line.startsWith("enum ") && line.contains("{") -> scopeStack.addLast("e" to "")
                line.startsWith("oneof ") && line.contains("{") -> scopeStack.addLast("o" to "")
                line.startsWith("service ") && line.contains("{") -> scopeStack.addLast("s" to "")
                line == "}" -> if (scopeStack.isNotEmpty()) scopeStack.removeLast()
            }

            // 字段检查：归属最近的外层 message
            val msgEntry = scopeStack.lastOrNull { it.first == "m" }
            if (msgEntry != null) {
                val m = FIELD_RE.find(line)
                if (m != null) {
                    val type = m.groupValues[1]
                    val name = m.groupValues[2]
                    if (type == "string" && isStateField(name)) {
                        val key = "${msgEntry.second}.$name"
                        if (key !in ALLOWED_STRING_STATUS_FIELDS) {
                            violations.add(
                                Violation(
                                    fileName, lineNo,
                                    "字段 '$key' 为 string 但语义是状态/角色，应使用 enum" +
                                        "（参考 P2 proto 债清理：DeadLetterStatus / GroupMemberRole / RiskLevel）"
                                )
                            )
                        }
                    }
                }
            }
        }
        return violations
    }

    private fun isStateField(name: String): Boolean =
        name.split("_").any { it in STATE_SEMANTIC_TERMS }

    companion object {
        /** 匹配字段声明，兼容 optional/repeated/required 前缀；等号后须为数字，排除 option/default 等非字段行 */
        private val FIELD_RE = Regex("""(?:optional\s+|repeated\s+|required\s+)?(\w+)\s+(\w+)\s*=\s*\d+""")
    }
}
