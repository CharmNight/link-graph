package com.charmnight.linkgraph.llm.context

/** 表示一段可参与 prompt 拼装的文本，附带优先级用于预算裁剪排序。 */
data class PromptSection(
    /** 当前片段的文本内容。 */
    val text: String,
    /** 当前片段的优先级，预算不足时优先保留高权重片段。 */
    val priority: PromptSectionPriority = PromptSectionPriority.BACKGROUND,
)

/** prompt 片段优先级，权重越高越优先保留。 */
enum class PromptSectionPriority(val weight: Int) {
    /** 历史对话记录，权重最低，可最早被裁剪。 */
    HISTORY(10),
    /** 一般背景信息。 */
    BACKGROUND(30),
    /** 图节点、边等结构性输入。 */
    GRAPH(40),
    /** 证据、差异、同步预览等结论性输入。 */
    EVIDENCE(60),
    /** 直接相关的源码片段。 */
    SOURCE(70),
    /** 已确认草稿变更。 */
    CONFIRMED_CHANGE(80),
    /** 行为规则、限制声明。 */
    BEHAVIOR_RULE(90),
    /** JSON Schema 说明。 */
    SCHEMA(95),
    /** 用户当前轮目标，权重最高，必须保留。 */
    USER_GOAL(100),
}

/** 表示一条 prompt 消息的角色：系统或用户。 */
enum class PromptMessageRole {
    SYSTEM,
    USER,
}

/** 表示拼装完成的 system / user 双段提示词。 */
data class PromptComposition(
    /** 最终系统提示词文本。 */
    val systemPrompt: String,
    /** 最终用户提示词文本。 */
    val userPrompt: String,
)

/**
 * 把最小上下文片段拼成最终 prompt。
 * 通过 [ContextBudgetController] 控制字符与估算 token 预算，并按 [PromptSectionPriority] 在预算内保留最重要的内容。
 */
class PromptComposer(
    /** 用于裁剪和估算预算的控制器。 */
    private val budgetController: ContextBudgetController = ContextBudgetController(),
) {
    /** 按优先级在预算内裁剪所有片段，并把保留下来的片段按双换行拼成单段文本。 */
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

    /**
     * 同时拼装 system 与 user 两段提示词。
     * 裁剪时统一排序，但拼装输出时再按角色分组，避免系统消息被用户消息冲掉。
     */
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

    /**
     * 按优先级与原索引顺序裁剪并保留可放入预算的片段。
     * 裁剪过程中逐段扣减字符与 token 预算，同时为每段预留下一段分隔符的开销。
     */
    private fun trimPrioritized(sections: List<IndexedPromptSection>): List<IndexedPromptSection> {
        var remaining = budgetController.maxCharacters
        var remainingTokens = budgetController.maxTokens
        var emittedCount = 0
        return sections
            .sortedWith(
                compareByDescending<IndexedPromptSection> { section -> section.section.priority.weight }
                    .thenBy { section -> section.index },
            )
            .mapNotNull { section ->
                if (remaining <= 0 || remainingTokens <= 0) {
                    return@mapNotNull null
                }
                val separatorCost = if (emittedCount == 0) 0 else 2
                val separatorTokenCost = if (emittedCount == 0) 0 else budgetController.estimateTokens("\n\n")
                if (remaining <= separatorCost || remainingTokens <= separatorTokenCost) {
                    return@mapNotNull null
                }
                val trimmedText = budgetController.trimSections(listOf(section.section.text.take(remaining - separatorCost)))
                    .firstOrNull()
                    ?.takeIf { text -> budgetController.estimateTokens(text) <= remainingTokens - separatorTokenCost }
                    ?: section.section.text
                        .asSequence()
                        .runningFold("") { acc, ch -> acc + ch }
                        .drop(1)
                        .takeWhile { text -> text.length <= remaining - separatorCost && budgetController.estimateTokens(text) <= remainingTokens - separatorTokenCost }
                        .lastOrNull()
                    ?: ""
                if (trimmedText.isBlank()) {
                    return@mapNotNull null
                }
                remaining -= trimmedText.length + separatorCost
                remainingTokens -= budgetController.estimateTokens(trimmedText) + separatorTokenCost
                emittedCount += 1
                section.copy(section = section.section.copy(text = trimmedText))
            }
    }

    /** 同时记录片段在原列表中的位置、消息角色与片段内容，用于稳定排序与重组。 */
    private data class IndexedPromptSection(
        val index: Int,
        val role: PromptMessageRole,
        val section: PromptSection,
    )
}
