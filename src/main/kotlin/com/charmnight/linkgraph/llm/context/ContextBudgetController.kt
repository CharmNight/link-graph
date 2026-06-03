package com.charmnight.linkgraph.llm.context

/**
 * 控制 prompt 上下文预算。
 * 当前先按字符数做最小裁剪，后续再按 token 估算细化。
 */
data class ContextBudgetController(
    /** 最大字符数。 */
    val maxCharacters: Int = 12_000,
    /** 最大估算 token 数。 */
    val maxTokens: Int = 3_000,
) {
    fun trim(text: String): String {
        val charTrimmed = text.take(maxCharacters)
        if (estimateTokens(charTrimmed) <= maxTokens) {
            return charTrimmed
        }
        val result = StringBuilder()
        charTrimmed.forEach { ch ->
            val candidate = result.toString() + ch
            if (estimateTokens(candidate) > maxTokens) {
                return result.toString()
            }
            result.append(ch)
        }
        return result.toString()
    }

    fun trimSections(sections: List<String>): List<String> {
        var remaining = maxCharacters
        var remainingTokens = maxTokens
        val result = mutableListOf<String>()
        sections.forEach { section ->
            if (remaining <= 0 || remainingTokens <= 0) {
                return@forEach
            }
            val trimmed = trimToBudgets(section, remaining, remainingTokens)
            if (trimmed.isNotBlank()) {
                result += trimmed
                remaining -= trimmed.length
                remainingTokens -= estimateTokens(trimmed)
            }
        }
        return result
    }

    fun estimateTokens(text: String): Int {
        var asciiWords = 0
        var inAsciiWord = false
        var nonAsciiChars = 0
        var punctuation = 0
        text.forEach { ch ->
            when {
                ch.code > 127 && !ch.isWhitespace() -> {
                    nonAsciiChars += 1
                    inAsciiWord = false
                }
                ch.isLetterOrDigit() || ch == '_' -> {
                    if (!inAsciiWord) {
                        asciiWords += 1
                        inAsciiWord = true
                    }
                }
                ch.isWhitespace() -> inAsciiWord = false
                else -> {
                    punctuation += 1
                    inAsciiWord = false
                }
            }
        }
        val punctuationTokens = (punctuation + 3) / 4
        return asciiWords + nonAsciiChars + punctuationTokens
    }

    private fun trimToBudgets(section: String, remainingCharacters: Int, remainingTokens: Int): String {
        val charTrimmed = section.take(remainingCharacters)
        if (estimateTokens(charTrimmed) <= remainingTokens) {
            return charTrimmed
        }
        val result = StringBuilder()
        charTrimmed.forEach { ch ->
            val candidate = result.toString() + ch
            if (estimateTokens(candidate) > remainingTokens) {
                return result.toString()
            }
            result.append(ch)
        }
        return result.toString()
    }
}
