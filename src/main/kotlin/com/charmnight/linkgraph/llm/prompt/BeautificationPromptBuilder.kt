package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.llm.AssistantPromptModeResolver
import com.charmnight.linkgraph.agent.model.GraphBeautificationContext
import com.charmnight.linkgraph.agent.model.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.BEHAVIOR_RULE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SOURCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.llm.effectiveEvidenceProfile
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.WorkbenchStep

/**
 * 链路讲解（美化）场景的 prompt builder（P2-1 深度拆分）。
 *
 * 基于稳定步骤、链路图展示上下文和真实源码片段，
 * 输出步骤化的讲解（steps + evidence + followUpQuestions）。
 */

/** 构造链路讲解场景的提示词包。 */
internal fun buildBeautificationPromptPackage(
    promptComposer: PromptComposer,
    context: GraphBeautificationContext,
    settings: LinkGraphSettingsState,
    projectedSteps: List<WorkbenchStep> = emptyList(),
): LlmPromptPackage {
    val graph = context.presentationContext.graph
    val nodes = graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val edges = graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
    val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
        sourceSnippetSummary(snippet)
    }.ifBlank { "- 无" }
    val steps = projectedSteps.joinToString("\n") { step ->
        "- ${step.stepId} | ${step.kind.name} | ${step.title} | nodeRefs=${step.nodeRefs.joinToString()}"
    }.ifBlank { "- 无" }
    val evidenceProfile = context.effectiveEvidenceProfile()
    val evidenceProfileText = buildEvidenceProfileText(evidenceProfile)
    val assistantPromptMode = AssistantPromptModeResolver.resolve(context, evidenceProfile)
    val classDescriptionGoal = assistantPromptMode.classDescriptionGoal
    val classRelationshipGoal = assistantPromptMode.classRelationshipGoal
    val followUp = context.followUp
    val followUpBlock = when {
        followUp != null -> {
            """
            讲解模式：追问讲解
            追问上下文：
            - 当前步骤ID：${followUp.stepId}
            - 当前步骤标题：${followUp.stepTitle}
            - 用户追问：${sanitizeUserField(followUp.question)}
            本轮回答必须先直接回答用户追问，再补充代码位置、关键条件/分支和下一跳方法。
            steps[0] 必须优先对应当前步骤；description 的首句必须先回答用户追问。
            如果当前证据不足，必须明确写出“不足以确认”，不要编造隐藏逻辑。
            """.trimIndent()
        }
        classDescriptionGoal -> {
            """
            讲解模式：介绍类模式
            本轮目标是介绍当前类图节点，不是解释方法调用链，也不是只解释边。
            必须覆盖：职责、核心字段/构造依赖、对外协作关系、典型使用场景，以及建议继续下钻的位置。
            不要把回答开头写成“这不是方法调用图”；如果证据有限，先介绍能从类图确认的结构事实，再说明不能确认的职责细节。
            steps[*].kind 优先使用 STRUCTURE_OVERVIEW；只有真实证据支持其他类型时才使用其他 kind。
            """.trimIndent()
        }
        classRelationshipGoal -> {
            """
            讲解模式：类图关系解释模式
            本轮只解释图上的结构关系：字段关联、构造参数、返回值、参数或局部类型依赖。
            不要把回答写成类职责介绍，不要按方法调用顺序讲解，也不要补出图上没有的隐藏业务步骤。
            steps[*].kind 优先使用 STRUCTURE_OVERVIEW。
            """.trimIndent()
        }
        !evidenceProfile.methodChainAllowed -> {
            """
            讲解模式：证据受限讲解
            当前锚点不是可直接解释为方法调用链的节点，必须按允许讲解模式输出。
            如果缺少方法级调用边，不能输出“定位被调方法”、调用链、当前方法内部流程或隐藏业务步骤。
            当前是类图/结构图关系时，应解释为字段关联、构造参数、返回值、参数或局部类型等结构关系，不要把类型依赖边写成方法调用顺序。
            必须先说明当前能确认的结构事实，再说明当前不能确认的关系和可下钻方向。
            """.trimIndent()
        }
        else -> {
            """
            讲解模式：常规讲解
            讲解重点：${context.explanationFocus ?: "先讲当前方法内部，再讲跨方法扩展"}
            """.trimIndent()
        }
    }
    val systemPrompt = """
        你是 IDEA Link Graph 的步骤化链路讲解助手。
        你的职责是基于稳定步骤、链路图展示上下文和真实源码片段，补齐每一步是做什么的。
        必须围绕给定 stepId 输出步骤说明，不允许退回成 summary/sections 报告卡。
        必须优先解释当前方法内部关键流程，再补充可继续下钻的方向，不能把图上的折叠部分误写成已展示事实。
        如果提供了追问上下文，必须把它视为本轮最高优先级，先回答用户追问，再补证据和下钻方向。
        每个步骤都必须输出 evidence 和 followUpQuestions。
        evidenceLevel 只允许：
        - DIRECT_SOURCE：直接来自当前提供的源码片段
        - DIRECT_GRAPH：直接来自当前图节点或图连线
        - CALLSITE_ONLY：当前只看到了调用点，没有看到被调实现
        - NOT_OBSERVED：当前提供的上下文没有直接观察到该行为
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
                你正在美化并讲解一张链路图。
                目标模型：${settings.sanitized().model}
                用户目标：${userGoalOrFallback(context.userGoal, "请提高链路图的可读性")}
                偏好风格：${context.preferredStyle ?: "未指定"}
                当前方法内部折叠节点：${context.presentationContext.hiddenCurrentMethodNodeCount}
                跨方法扩展折叠节点：${context.presentationContext.hiddenCrossMethodNodeCount}
                锚点节点：${context.presentationContext.anchorNodeId ?: "未指定"}
                当前粒度：${context.granularity.name}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                图证据边界：
                $evidenceProfileText
                """.trimIndent(),
                priority = BEHAVIOR_RULE,
            ),
            PromptSection(followUpBlock, priority = BEHAVIOR_RULE),
            PromptSection(
                """
                稳定步骤：
                $steps
                """.trimIndent(),
                priority = EVIDENCE,
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
            PromptSection(beautificationSchemaInstruction(), priority = SCHEMA),
        ),
    )
}

/** 返回链路讲解场景的用户提示词。 */
internal fun buildBeautificationPrompt(
    promptComposer: PromptComposer,
    context: GraphBeautificationContext,
    settings: LinkGraphSettingsState,
): String = buildBeautificationPromptPackage(promptComposer, context, settings).userPrompt
