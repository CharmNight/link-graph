package com.charmnight.linkgraph.llm.context

/**
 * 控制 prompt 上下文预算。
 * 当前先按字符数做最小裁剪，后续再按 token 估算细化。
 */
data class ContextBudgetController(
    /** 最大字符数。 */
    val maxCharacters: Int = 12_000,
) {
    fun trim(text: String): String = text.take(maxCharacters)

    fun trimSections(sections: List<String>): List<String> {
        var remaining = maxCharacters
        val result = mutableListOf<String>()
        sections.forEach { section ->
            if (remaining <= 0) {
                return@forEach
            }
            val trimmed = section.take(remaining)
            if (trimmed.isNotBlank()) {
                result += trimmed
                remaining -= trimmed.length
            }
        }
        return result
    }
}
