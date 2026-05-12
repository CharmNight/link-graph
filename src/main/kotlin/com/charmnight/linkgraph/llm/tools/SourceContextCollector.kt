package com.charmnight.linkgraph.llm.tools

class SourceContextCollector {
    fun collect(
        lines: List<String>,
        startLine: Int?,
        endLine: Int?,
        focusedSnippet: String,
    ): String {
        if (startLine == null || endLine == null) {
            return focusedSnippet
        }
        val importBlock = lines
            .filter { line -> line.trim().startsWith("import ") }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
        val classContext = collectClassContext(lines, startLine)
        val neighborContext = collectNeighborContext(lines, startLine, endLine)
        return listOfNotNull(
            importBlock?.let { "imports:\n$it" },
            classContext?.let { "class context:\n$it" },
            neighborContext?.let { "neighbor context:\n$it" },
            "current method:\n$focusedSnippet",
        ).joinToString("\n\n")
    }

    private fun collectClassContext(
        lines: List<String>,
        startLine: Int,
    ): String? {
        val startIndex = (startLine - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val classIndex = (startIndex downTo 0).firstOrNull { index ->
            CLASS_DECLARATION_REGEX.containsMatchIn(lines[index])
        } ?: return null
        var contextStart = classIndex
        while (contextStart > 0 && lines[contextStart - 1].trim().startsWith("@")) {
            contextStart -= 1
        }
        val contextEnd = generateSequence(classIndex) { index -> (index + 1).takeIf { it < lines.size } }
            .firstOrNull { index -> lines[index].contains("{") }
            ?: classIndex
        return lines.subList(contextStart, (contextEnd + 1).coerceAtMost(lines.size))
            .joinToString("\n")
            .trim()
            .takeIf(String::isNotBlank)
    }

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
        private val CLASS_DECLARATION_REGEX = Regex("\\b(class|interface|enum|record)\\b")
    }
}
