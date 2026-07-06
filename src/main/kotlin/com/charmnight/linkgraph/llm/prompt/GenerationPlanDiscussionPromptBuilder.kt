package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.BEHAVIOR_RULE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.CONFIRMED_CHANGE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.HISTORY
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession

/**
 * 实现建议追问场景的 prompt builder（P2-1 深度拆分）。
 *
 * 围绕当前已生成的实现建议回答用户问题，不引入新的风险线程 / 候选变更 / patch。
 */

/** 构造实现建议追问场景的提示词包。 */
internal fun buildGenerationPlanDiscussionPromptPackage(
    promptComposer: PromptComposer,
    context: GenerationContext,
    plan: GenerationPlan,
    question: String,
    settings: LinkGraphSettingsState,
    session: GenerationPlanDiscussionSession? = null,
    focusItemId: String? = null,
): LlmPromptPackage {
    val confirmedChanges = context.confirmedChanges
        .joinToString("\n") { change -> confirmedChangeSummary(change, context.graph) }
        .ifBlank { "- 无" }
    val planItems = plan.items.joinToString("\n") { item ->
        buildString {
            append("- ").append(item.id).append(" | ").append(item.title)
            if (item.targetPath != null) {
                append(" | targetPath=").append(item.targetPath)
            }
            if (item.description.isNotBlank()) {
                append(" | description=").append(item.description)
            }
        }
    }.ifBlank { "- 无" }
    val history = session?.messages?.joinToString("\n") { message ->
        // message.content 是历史会话文本（用户输入或 LLM 输出），按不可信 sanitize
        "- [${message.role.name}] ${sanitizeContent(message.content)}"
    }?.ifBlank { "- 无" } ?: "- 无"
    val focusItem = focusItemId
        ?.let { targetId -> plan.items.firstOrNull { item -> item.id == targetId } }
        ?.let { item ->
            buildString {
                append(item.id).append(" | ").append(item.title)
                if (item.targetPath != null) {
                    append(" | targetPath=").append(item.targetPath)
                }
                if (item.description.isNotBlank()) {
                    append(" | description=").append(item.description)
                }
            }
        }
        ?: "未指定"
    val systemPrompt = """
        你是 IDEA Link Graph 的实现建议追问助手。
        你的职责是围绕“当前已经生成的实现建议”回答用户问题。
        你只能解释、澄清、细化当前实现建议；不要把用户重新导向风险问答，也不要生成新的风险线程、候选变更或草稿 patch。
        如果用户质疑某条建议，优先解释这条建议的原因、影响范围、可替代方案和边界，而不是回到链路问答取证。
        回答必须明确：这是对当前实现建议的补充说明，不是新的风险裁决。
        只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
        即使信息不足，也必须返回合法 JSON；warnings 使用 []。
        $USER_INPUT_CONTRACT
    """.trimIndent()
    return buildPromptPackage(
        promptComposer = promptComposer,
        systemPrompt = systemPrompt,
        userSections = listOf(
            PromptSection(
                """
                你正在回答用户对“当前实现建议”的追问。
                目标模型：${settings.sanitized().model}
                用户问题：${sanitizeUserField(question)}
                当前聚焦条目：${sanitizeUserField(focusItem)}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                当前实现建议摘要：
                ${plan.summary}
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                当前实现建议条目：
                $planItems
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                已确认草稿变更：
                $confirmedChanges
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            budgetedPromptSection(
                header = "当前工作图节点：",
                items = context.graph.nodes,
                priority = GRAPH,
                renderItem = ::nodeSummary,
            ),
            budgetedPromptSection(
                header = "当前工作图连线：",
                items = context.graph.edges,
                priority = GRAPH,
                renderItem = ::edgeSummary,
            ),
            PromptSection(
                """
                历史追问：
                $history
                """.trimIndent(),
                priority = HISTORY,
            ),
            PromptSection(
                """
                只回答这份实现建议本身：
                - 可以解释为什么这样建议
                - 可以指出更小改法、替代拆法、影响范围
                - 不要让用户跳回风险问答
                - 不要输出新的 investigationThreads、candidateChanges 或 patch
                """.trimIndent(),
                priority = BEHAVIOR_RULE,
            ),
            PromptSection(generationPlanDiscussionSchemaInstruction(), priority = SCHEMA),
        ),
    )
}
