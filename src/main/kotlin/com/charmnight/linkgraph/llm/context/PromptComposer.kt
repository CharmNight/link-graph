package com.charmnight.linkgraph.llm.context

data class PromptSection(
    val text: String,
    val priority: PromptSectionPriority = PromptSectionPriority.BACKGROUND,
)

enum class PromptSectionPriority(val weight: Int) {
    HISTORY(10),
    BACKGROUND(30),
    GRAPH(40),
    EVIDENCE(60),
    SOURCE(70),
    CONFIRMED_CHANGE(80),
    BEHAVIOR_RULE(90),
    SCHEMA(95),
    USER_GOAL(100),
}

enum class PromptMessageRole {
    SYSTEM,
    USER,
}

data class PromptComposition(
    val systemPrompt: String,
    val userPrompt: String,
)

/**
 * 把最小上下文片段拼成最终 prompt。
 */
class PromptComposer(
    private val budgetController: ContextBudgetController = ContextBudgetController(),
) {
    fun composePrioritized(sections: List<PromptSection>): String {
        return trimPrioritized(
            sections.mapIndexed { index, section ->
                IndexedPromptSection(index, PromptMessageRole.USER, section)
            },
        )
            .map(IndexedPromptSection::section)
            .map(PromptSection::text)
            .joinToString(separator = "\n\n")
            .trim()
    }

    fun composeMessages(
        systemSections: List<PromptSection>,
        userSections: List<PromptSection>,
    ): PromptComposition {
        val sections = systemSections.mapIndexed { index, section ->
            IndexedPromptSection(index, PromptMessageRole.SYSTEM, section)
        } + userSections.mapIndexed { index, section ->
            IndexedPromptSection(index + systemSections.size, PromptMessageRole.USER, section)
        }
        val trimmed = trimPrioritized(sections)
        return PromptComposition(
            systemPrompt = trimmed
                .filter { section -> section.role == PromptMessageRole.SYSTEM }
                .joinToString(separator = "\n\n") { section -> section.section.text }
                .trim(),
            userPrompt = trimmed
                .filter { section -> section.role == PromptMessageRole.USER }
                .joinToString(separator = "\n\n") { section -> section.section.text }
                .trim(),
        )
    }

    private fun trimPrioritized(sections: List<IndexedPromptSection>): List<IndexedPromptSection> {
        var remaining = budgetController.maxCharacters
        var emittedCount = 0
        return sections
            .sortedWith(
                compareByDescending<IndexedPromptSection> { section -> section.section.priority.weight }
                    .thenBy { section -> section.index },
            )
            .mapNotNull { section ->
                if (remaining <= 0) {
                    return@mapNotNull null
                }
                val separatorCost = if (emittedCount == 0) 0 else 2
                if (remaining <= separatorCost) {
                    return@mapNotNull null
                }
                val trimmedText = section.section.text.take(remaining - separatorCost)
                if (trimmedText.isBlank()) {
                    return@mapNotNull null
                }
                remaining -= trimmedText.length + separatorCost
                emittedCount += 1
                section.copy(section = section.section.copy(text = trimmedText))
            }
    }

    private data class IndexedPromptSection(
        val index: Int,
        val role: PromptMessageRole,
        val section: PromptSection,
    )
}
