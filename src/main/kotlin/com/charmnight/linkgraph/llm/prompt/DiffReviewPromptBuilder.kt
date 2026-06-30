package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.agent.model.GraphDiffContext
import com.charmnight.linkgraph.agent.model.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.BEHAVIOR_RULE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 设计 vs 代码差异审查场景的 prompt builder（P2-1 深度拆分）。
 *
 * 解释 Mermaid 设计基线与代码事实图之间的差异，并输出只写入草稿层的修订 patch。
 */

/** 构造差异审查场景的提示词包。 */
internal fun buildDiffReviewPromptPackage(
    promptComposer: PromptComposer,
    context: GraphDiffContext,
    question: String,
    settings: LinkGraphSettingsState,
): LlmPromptPackage {
    val factNodes = context.factGraph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val designNodes = context.designBaseline.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val diff = context.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
    val focusedDiffs = context.diff.entries
        .filter { entry -> entry.elementId in context.selectedDiffItemIds }
        .joinToString("\n") { entry -> diffSummary(entry) }
        .ifBlank { "- 无" }
    val reviewEvidence = context.reviewEvidenceBundle.ifBlank { "- 无" }
    val systemPrompt = """
        你是 IDEA Link Graph 的设计差异审查助手。
        你的职责是解释 Mermaid 设计基线与代码事实图之间的差异，并输出只写入草稿层的修订 patch。
        必须优先围绕当前关注的差异焦点给出建议，避免泛泛而谈。
        不允许把修订建议伪装成代码事实。
        answer 与 patch 之外，还必须输出 findings，对每条关键结论标注证据等级和引用。
        evidenceLevel 只允许：
        - DIRECT_SOURCE：直接来自当前提供的源码片段
        - DIRECT_GRAPH：直接来自当前图节点或图连线
        - CALLSITE_ONLY：当前只看到了调用点，没有看到被调实现
        - NOT_OBSERVED：当前提供的上下文没有直接观察到该行为
        patch.operations[*].metadata 必须补充 "draft.claimType"，可选值仅允许：
        - CODE_FACT：源码中可以直接定位和验证的事实性说明
        - RISK_HINT：基于当前代码边界得出的风险或异常提醒
        - EXPLANATION_NOTE：帮助阅读链路的解释性注释
        - STRUCTURAL_SUGGESTION：结构补全、补图、待补节点/连线建议
        只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
        即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
        $USER_INPUT_CONTRACT
    """.trimIndent()
    return buildPromptPackage(
        promptComposer = promptComposer,
        systemPrompt = systemPrompt,
        userSections = listOf(
            PromptSection(
                """
                你正在做“设计图基线 vs 代码事实图”的差异审查。
                目标模型：${settings.sanitized().model}
                用户问题：${sanitizeUserField(question)}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                当前关注差异：
                $focusedDiffs
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                左侧设计基线节点：
                $designNodes
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                右侧代码事实节点：
                $factNodes
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                当前差异：
                $diff
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                Review Graph 最小证据包：
                $reviewEvidence
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                "请先解释差异，再给出只写入草稿层的修订 patch 建议。不要直接修改代码事实。",
                priority = BEHAVIOR_RULE,
            ),
            PromptSection(diffReviewSchemaInstruction(), priority = SCHEMA),
        ),
    )
}

/** 返回差异审查场景的用户提示词。 */
internal fun buildDiffReviewPrompt(
    promptComposer: PromptComposer,
    context: GraphDiffContext,
    question: String,
    settings: LinkGraphSettingsState,
): String =
    buildDiffReviewPromptPackage(promptComposer, context, question, settings).userPrompt
