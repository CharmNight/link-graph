package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.CONFIRMED_CHANGE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SOURCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 实现计划生成 + 实现建议追问场景的 prompt builder（P2-1 深度拆分）。
 */

/** 构造实现计划生成场景的提示词包。 */
internal fun buildGenerationPromptPackage(
    promptComposer: PromptComposer,
    snapshot: GenerationContext,
    settings: LinkGraphSettingsState,
): LlmPromptPackage {
    val nodes = snapshot.graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val edges = snapshot.graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
    val issues = snapshot.mermaidIssues.joinToString("\n") { issue ->
        "- [${issue.category.name}] ${issue.code}: ${issue.message}"
    }.ifBlank { "- 无" }
    val diff = snapshot.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
    val syncPreview = snapshot.syncPreviewItems.joinToString("\n") { item ->
        "- [${item.risk.name}] ${item.title}: ${item.description}"
    }.ifBlank { "- 无" }
    val confirmedChanges = snapshot.confirmedChanges
        .joinToString("\n") { change -> confirmedChangeSummary(change, snapshot.graph) }
        .ifBlank { "- 无" }
    val sourceSnippets = snapshot.sourceContext.joinToString("\n") { snippet ->
        sourceSnippetSummary(snippet)
    }.ifBlank { "- 无" }
    val systemPrompt = """
        你是 IDEA Link Graph 的实现计划生成器。
        你的职责是基于链路图、已确认草稿变更、真实源码片段、Mermaid 问题和同步预览，输出结构化实现计划。
        已确认草稿变更代表用户已经确认要改的真实目标，你必须优先围绕这些确认项生成计划，不要被无关图节点带偏。
        下方“相关源码片段”来自当前项目的真实源码；如果某个目标已经给出对应片段，禁止声称未提供源码上下文。
        只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
        即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
        计划必须面向真实代码改动，避免空泛建议。
        $USER_INPUT_CONTRACT
    """.trimIndent()
    return buildPromptPackage(
        promptComposer = promptComposer,
        systemPrompt = systemPrompt,
        userSections = listOf(
            PromptSection(
                """
                你正在根据链路图设计评审结果生成代码实现计划。
                目标模型：${settings.sanitized().model}
                用户目标：${userGoalOrFallback(snapshot.userGoal, "请根据当前草稿、图差异和同步预览生成实现建议。")}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                已确认草稿变更：
                $confirmedChanges
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            PromptSection(
                """
                相关源码片段：
                $sourceSnippets
                """.trimIndent(),
                priority = SOURCE,
            ),
            PromptSection(
                """
                图节点：
                $nodes
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                图连线：
                $edges
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                Mermaid 校验问题：
                $issues
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                图差异：
                $diff
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                同步预览：
                $syncPreview
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(generationPlanSchemaInstruction(), priority = SCHEMA),
        ),
    )
}

/** 返回实现计划生成场景的用户提示词。 */
internal fun buildGenerationPrompt(
    promptComposer: PromptComposer,
    snapshot: GenerationContext,
    settings: LinkGraphSettingsState,
): String = buildGenerationPromptPackage(promptComposer, snapshot, settings).userPrompt
