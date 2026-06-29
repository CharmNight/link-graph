package com.charmnight.linkgraph.ui.bridge

/**
 * 从 Kotlin 源码文本中按括号配对提取函数体，不依赖缩进格式。
 *
 * 替代早期的「`\n    private fun ` 字面量定位」启发式——后者依赖 4-space 缩进，
 * ktlint 改 2-space 或函数变 top-level 时会 silently 失效。
 *
 * 实现要点：
 * - 找到 `fun <name>(` 后，从第一个 `{` 开始括号配对到匹配的 `}`
 * - 跳过字符串字面量（含三引号 `"""..."""`）、字符字面量、行注释、块注释
 * - 字符串内的 `{` / `}` 不影响深度
 *
 * 已知边角：模板字符串里的嵌套字符串（如 `"${"{"}"`）不正确处理；
 * BridgeCommandParser.kt 当前结构不涉及，作为已知限制。
 */
internal object KotlinSourceExtractor {
    /**
     * 提取 [functionName] 对应函数的源码片段（含 `fun name(...)` 签名到匹配的 `}`）。
     * 找不到函数或括号不匹配返回 null。
     */
    fun extractFunction(source: String, functionName: String): String? {
        val fnStart = source.indexOf("fun $functionName(")
        if (fnStart < 0) return null

        var idx = fnStart
        var braceDepth = 0
        var bodyStarted = false
        var inString = false
        var inTripleString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false

        while (idx < source.length) {
            val c = source[idx]
            val next = source.getOrNull(idx + 1)
            val tripleAhead = if (idx + 3 <= source.length) source.substring(idx, idx + 3) else ""

            when {
                inLineComment -> {
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        idx++
                    }
                }
                inTripleString -> {
                    if (c == '"' && tripleAhead == "\"\"\"") {
                        inTripleString = false
                        idx += 2
                    }
                }
                inString -> {
                    when (c) {
                        '\\' -> idx++ // 跳过转义字符
                        '"' -> inString = false
                    }
                }
                inChar -> {
                    when (c) {
                        '\\' -> idx++
                        '\'' -> inChar = false
                    }
                }
                else -> {
                    when {
                        c == '/' && next == '/' -> { inLineComment = true; idx++ }
                        c == '/' && next == '*' -> { inBlockComment = true; idx++ }
                        c == '"' && tripleAhead == "\"\"\"" -> { inTripleString = true; idx += 2 }
                        c == '"' -> inString = true
                        c == '\'' -> inChar = true
                        c == '{' -> {
                            braceDepth++
                            bodyStarted = true
                        }
                        c == '}' -> {
                            braceDepth--
                            if (bodyStarted && braceDepth == 0) {
                                return source.substring(fnStart, idx + 1)
                            }
                        }
                    }
                }
            }
            idx++
        }
        return null
    }
}
