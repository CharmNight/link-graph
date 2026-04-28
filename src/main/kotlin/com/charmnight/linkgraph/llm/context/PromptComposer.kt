package com.charmnight.linkgraph.llm.context

/**
 * 把最小上下文片段拼成最终 prompt。
 */
class PromptComposer(
    private val budgetController: ContextBudgetController = ContextBudgetController(),
) {
    fun compose(sections: List<String>): String {
        return budgetController.trimSections(sections)
            .joinToString(separator = "\n\n")
            .trim()
    }
}
