package com.charmnight.linkgraph.llm.context

/**
 * 控制 prompt 上下文预算。
 * 当前先按字符数做最小裁剪，后续再按 token 估算细化。
 */
data class ContextBudgetController(
    /** 单次拼装 prompt 时允许保留的最大字符数。 */
    val maxCharacters: Int = 12_000,
    /** 单次拼装 prompt 时允许保留的最大估算 token 数。 */
    val maxTokens: Int = 3_000,
) {
    /** 在字符数与估算 token 数双重约束下裁剪文本，返回尽可能保留前缀的结果。 */
    fun trim(text: String): String {
        return trim(text, maxCharacters, maxTokens)
    }

    /** 在指定字符和 token 上限内裁剪文本，返回尽可能保留前缀的结果。 */
    fun trim(
        text: String,
        characterLimit: Int,
        tokenLimit: Int,
    ): String {
        if (characterLimit <= 0 || tokenLimit <= 0) {
            return ""
        }
        val charTrimmed = text.take(characterLimit)
        if (estimateTokens(charTrimmed) <= tokenLimit) {
            return charTrimmed
        }
        return trimToBudgets(text, characterLimit, tokenLimit)
    }

    /**
     * 在总预算内依次裁剪多段文本。
     * 先消费完前段预算再处理后段，预算耗尽后剩余段会被丢弃。
     */
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

    /**
     * 估算文本大致 token 数。
     * 按 ASCII 单词、非 ASCII 单字符与标点四字符一组综合计算，避免引入完整分词器依赖。
     */
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

    /** 在指定剩余预算内裁剪单段文本，规则与 [trim] 一致。 */
    private fun trimToBudgets(section: String, remainingCharacters: Int, remainingTokens: Int): String {
        val charTrimmed = section.take(remainingCharacters)
        if (estimateTokens(charTrimmed) <= remainingTokens) {
            return charTrimmed
        }
        var lowerInclusive = 0
        var upperExclusive = charTrimmed.length + 1
        while (lowerInclusive + 1 < upperExclusive) {
            val midpoint = (lowerInclusive + upperExclusive) / 2
            if (estimateTokens(charTrimmed.take(midpoint)) <= remainingTokens) {
                lowerInclusive = midpoint
            } else {
                upperExclusive = midpoint
            }
        }
        return charTrimmed.take(lowerInclusive)
    }
}
