package com.charmnight.linkgraph.llm.tools

/**
 * 源码上下文收集器。
 *
 * 给定一份代码片段（focused snippet），从同一份文件的完整行列表中
 * 收集额外的上下文：import 块、所在类声明、相邻若干行。
 * 让模型在理解某段代码时能拿到必要的周边信息，而不是孤立的片段。
 */
class SourceContextCollector {
    /**
     * 收集完整上下文。
     *
     * @param lines 文件全部行
     * @param startLine 焦点片段的起始行号（1-based）
     * @param endLine 焦点片段的结束行号
     * @param focusedSnippet 焦点片段文本
     * @return 拼接好的上下文，包含 imports / class / neighbor / current method 四段
     */
    fun collect(
        lines: List<String>,
        startLine: Int?,
        endLine: Int?,
        focusedSnippet: String,
    ): String {
        // 缺少行号时无法收集周边上下文，只返回焦点片段
        if (startLine == null || endLine == null) {
            return focusedSnippet
        }
        // import 块：抽出所有 import 开头的行
        val importBlock = lines
            .filter { line -> line.trim().startsWith("import ") }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
        // 类声明上下文
        val classContext = collectClassContext(lines, startLine)
        // 相邻行上下文
        val neighborContext = collectNeighborContext(lines, startLine, endLine)
        return listOfNotNull(
            importBlock?.let { "imports:\n$it" },
            classContext?.let { "class context:\n$it" },
            neighborContext?.let { "neighbor context:\n$it" },
            "current method:\n$focusedSnippet",
        ).joinToString("\n\n")
    }

    /**
     * 收集类声明上下文。
     *
     * 从焦点行向上查找最近的 class/interface/enum/record 声明，
     * 同时回带声明上方的注解（@xxx），向下扩展到声明后的 `{`。
     */
    private fun collectClassContext(
        lines: List<String>,
        startLine: Int,
    ): String? {
        val startIndex = (startLine - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        // 从焦点行向上找最近的类型声明
        val classIndex = (startIndex downTo 0).firstOrNull { index ->
            CLASS_DECLARATION_REGEX.containsMatchIn(lines[index])
        } ?: return null
        // 向上扩展到包含注解（@xxx 开头）
        var contextStart = classIndex
        while (contextStart > 0 && lines[contextStart - 1].trim().startsWith("@")) {
            contextStart -= 1
        }
        // 向下扩展到 `{`，覆盖声明头部的所有参数列表等
        val contextEnd = generateSequence(classIndex) { index -> (index + 1).takeIf { it < lines.size } }
            .firstOrNull { index -> lines[index].contains("{") }
            ?: classIndex
        return lines.subList(contextStart, (contextEnd + 1).coerceAtMost(lines.size))
            .joinToString("\n")
            .trim()
            .takeIf(String::isNotBlank)
    }

    /**
     * 收集相邻行上下文。
     * 取焦点片段前 3 行与后 3 行，让模型看到上下文中的相邻代码。
     */
    private fun collectNeighborContext(
        lines: List<String>,
        startLine: Int,
        endLine: Int,
    ): String? {
        val startIndex = (startLine - 1).coerceAtLeast(0)
        val endIndexExclusive = endLine.coerceAtMost(lines.size)
        val before = lines.subList((startIndex - 3).coerceAtLeast(0), startIndex)
        val after = lines.subList(endIndexExclusive, (endIndexExclusive + 3).coerceAtMost(lines.size))
        return (before + after)
            .joinToString("\n")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private companion object {
        /** 类型声明关键字正则。 */
        private val CLASS_DECLARATION_REGEX = Regex("\\b(class|interface|enum|record)\\b")
    }
}
