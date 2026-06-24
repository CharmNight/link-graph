package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.GraphQaScopeResolver
import com.charmnight.linkgraph.llm.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.BEHAVIOR_RULE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.CONFIRMED_CHANGE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.HISTORY
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SOURCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.llm.effectiveEvidenceProfile
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMode

/**
 * 链路问答场景的 prompt builder（P2-1 深度拆分）。
 *
 * 按 effectiveMode（AUTO / ANSWER / REVIEW / CHANGE / INVESTIGATE）输出对应的
 * findings / candidateChanges / investigationThreads / patch 结构。
 */

/** 构造链路问答场景的提示词包。 */
internal fun buildQaPromptPackage(
    promptComposer: PromptComposer,
    context: GraphQaContext,
    question: String,
    settings: LinkGraphSettingsState,
    session: QaConversationSession? = null,
    requestedMode: QaMode = QaMode.AUTO,
    effectiveMode: QaMode = QaMode.AUTO,
): LlmPromptPackage {
    val scopeNodes = GraphQaScopeResolver.resolveScopeNodes(context)
    val scopeEdges = GraphQaScopeResolver.resolveScopeEdges(context, scopeNodes)
    val scopeText = if (context.selectedNodeIds.isEmpty()) {
        "整图"
    } else {
        "框选组（${context.selectedNodeIds.size} 个节点）"
    }
    val selectedNodes = scopeNodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val selectedEdges = scopeEdges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
    val evidenceProfile = context.effectiveEvidenceProfile()
    val evidenceProfileText = buildEvidenceProfileText(evidenceProfile)
    val history = session?.messages?.joinToString("\n") { message ->
        "- [${message.role.name}] ${message.content}"
    }?.ifBlank { "- 无" } ?: "- 无"
    val existingChanges = session?.candidateChanges?.joinToString("\n") { change ->
        "- ${change.changeId} | ${change.title} | before=${change.beforeState ?: "无"} | after=${change.afterState ?: "无"}"
    }?.ifBlank { "- 无" } ?: "- 无"
    val existingInvestigationThreads = session?.investigationThreads?.joinToString("\n") { thread ->
        "- ${thread.threadId} | ${thread.title} | gap=${thread.evidenceGap.ifBlank { "未标注" }} | next=${thread.recommendedQuestion.ifBlank { "未标注" }}"
    }?.ifBlank { "- 无" } ?: "- 无"
    val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
        sourceSnippetSummary(snippet)
    }.ifBlank { "- 无" }
    val evidenceTrace = context.evidenceTrace.joinToString("\n") { trace ->
        buildString {
            append("- node=").append(trace.nodeId)
            trace.resolvedNodeId?.let { append(" | resolvedNode=").append(it) }
            append(" | path=").append(trace.filePath)
            trace.startLine?.let { append(" | startLine=").append(it) }
            trace.endLine?.let { append(" | endLine=").append(it) }
            append(" | reason=").append(trace.reason)
            if (trace.mappingTrace.isNotEmpty()) {
                append(" | mappingTrace=").append(trace.mappingTrace.joinToString(" -> "))
            }
            append(" | includedInPrompt=").append(trace.includedInPrompt)
        }
    }.ifBlank { "- 无" }
    val systemPrompt = """
        你是 IDEA Link Graph 的链路问答助手。
        你的职责必须服从本轮实际模式 effectiveMode，不能默认推进风险复核或草稿。
        模式边界：
        - AUTO 模式：按用户问题和证据自然分流；只有明确修改意图且有直接证据时才生成 candidateChanges，只有明确风险/证据缺口时才生成 investigationThreads。
        - ANSWER 模式：先直接回答用户问题，基于源码、图事实和取证轨迹解释；不要生成 candidateChanges，不要生成 investigationThreads，不要把证据不足转成草稿建议。
        - REVIEW 模式：找风险和证据缺口，允许 investigationThreads；不要生成 candidateChanges，不要冒充代码修改。
        - CHANGE 模式：只有存在 DIRECT_SOURCE 或 DIRECT_GRAPH 直接证据时才生成 candidateChanges；候选变更必须可追溯。
        - INVESTIGATE 模式：只围绕 sourceThreadId 对应风险线程继续取证，不生成无关新线程。
        图中没有调用边，不等于方法无法触发；必须结合源码注解、配置、框架回调、调用点和取证轨迹判断。
        你的第一优先级是直接回答“用户问题”，不要绕开问题泛化输出通用问答结论。
        必须遵守图证据边界；如果图证据边界禁止某类声明，即使用户问题要求，也只能说明证据不足和可下钻方向，不能补造事实。
        如果锚点不是 METHOD/FLOW_ACTION/FLOW_SCOPE/TERMINAL，不能把它称为当前方法，不能输出“定位被调方法”或方法调用链，除非图证据边界明确允许 METHOD_CHAIN。
        如果用户问题是在“介绍 / 解释 / 讲解链路”，answer 必须先解释链路本身，不要输出无关风险建议。
        只有当用户问题明确要求排查问题、找问题、调整逻辑，或者你发现了与用户问题直接相关且证据充分的缺陷时，才允许输出 candidateChanges；否则 candidateChanges 必须返回 []。
        candidateChanges[*] 必须绑定到 findings 中的 supportingFindingIds；如果没有可追溯 findings，就不要输出这条 candidateChange。
        如果 candidateChanges[*] 表示真实流程改动，必须提供 patchIntent；禁止只写自然语言然后让后端猜“是修改现有节点还是新增节点”。
        patchIntent.mode 只允许：UPDATE_EXISTING_NODE、INSERT_NEW_DECISION、INSERT_NEW_ACTION、ANNOTATION_ONLY。
        UPDATE_EXISTING_NODE 与 ANNOTATION_ONLY 必须提供 patchIntent.targetNodeId，且该 ID 必须是当前可编辑图中的真实节点 ID。
        INSERT_NEW_ACTION 与 INSERT_NEW_DECISION 必须提供 patchIntent.attachEdgeId，且该 ID 必须是当前可编辑图中的真实 CONTROL_FLOW 边 ID。
        INSERT_NEW_DECISION 还必须提供 patchIntent.falseBranchTargetNodeId，明确 FALSE 分支落到哪个真实节点；禁止让后端猜 FALSE 分支。
        对 if/switch/循环/条件/分支 的修改，优先表达为 UPDATE_EXISTING_NODE 或 INSERT_NEW_DECISION；禁止把这类改动写到 try/catch 等 flowchart.kind=SCOPE 容器节点上。
        当 candidateChanges[*] 已提供 patchIntent 时，graphPatch 可以省略，由后端依据 patchIntent 合成真实 patch；如果你提供 graphPatch，也必须与 patchIntent 语义一致。
        “事实图”表示代码事实基线；“当前可编辑图”表示当前工作台里可用于定位节点 ID、边 ID 和 graphPatch 落点的图。不要把当前可编辑图误称为事实图。
        当你描述 DIRECT_GRAPH 证据时，必须明确是来自“事实图”还是“当前可编辑图”；如果结论依赖真实控制流节点 ID、边 ID 或 patch 落点，只能基于“当前可编辑图”。
        只有当 candidateChanges[*] 是纯解释性补充、不会改变真实流程结构时，才允许使用 EXPLANATION_NOTE，并且此时 patchIntent.mode 必须是 ANNOTATION_ONLY。
        investigationThreads 用来表达“怀疑点 / 需要继续取证的线程”，它们不能冒充已经确认的变更，也不能写成草稿结论。
        如果证据等级只有 CALLSITE_ONLY 或 NOT_OBSERVED，就不要输出 candidateChanges，改为输出 investigationThreads。
        investigationThreads[*] 也必须绑定到 findings 中的 supportingFindingIds；如果没有可追溯 findings，就不要输出这条 investigationThread。
        禁止输出与用户问题无关的通用安全、性能、规范性建议。
        不允许把推测内容伪装成代码事实。
        answer、candidateChanges、investigationThreads 之外，还必须输出 findings，对每条关键结论标注证据等级和引用。
        evidenceLevel 只允许：
        - DIRECT_SOURCE：直接来自当前提供的源码片段
        - DIRECT_GRAPH：直接来自当前图节点或图连线
        - CALLSITE_ONLY：当前只看到了调用点，没有看到被调实现
        - NOT_OBSERVED：当前提供的上下文没有直接观察到该行为
        candidateChanges[*].status 只允许：
        - PENDING_CONFIRMATION
        - CONFIRMED
        - REJECTED
        - SUPERSEDED
        回答必须优先围绕当前选中范围作答；如果当前范围不足以支撑结论，再明确说明你借助了整图上下文。
        回答必须先给当前轮结论，再给待确认候选变更。不要直接改写草稿层。
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
                你正在做链路图问答。
                目标模型：${settings.sanitized().model}
                请求模式：${requestedMode.name}
                实际模式：${effectiveMode.name}
                当前范围：$scopeText
                用户问题：${sanitizeUserField(question)}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                当前范围节点：
                $selectedNodes
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                当前范围边：
                $selectedEdges
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                图证据边界：
                $evidenceProfileText
                """.trimIndent(),
                priority = BEHAVIOR_RULE,
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
                本轮取证轨迹：
                $evidenceTrace
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                图上下文边界：
                - 本轮 prompt 只包含“当前范围节点/边”、真实源码片段、取证轨迹、历史消息和工作台状态。
                - 如需整图、邻接节点、架构索引、Review Graph 或源码细节，必须按用户问题调用工具查询最小必要上下文。
                - 不要依据未进入本轮 prompt 的事实图或当前可编辑图内容下结论。
                """.trimIndent(),
                priority = BEHAVIOR_RULE,
            ),
            PromptSection(
                """
                历史消息：
                $history
                """.trimIndent(),
                priority = HISTORY,
            ),
            PromptSection(
                """
                已有待确认候选变更：
                $existingChanges
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            PromptSection(
                """
                已有风险线程：
                $existingInvestigationThreads
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            PromptSection(qaBehaviorInstruction(), priority = BEHAVIOR_RULE),
            PromptSection(qaSchemaInstruction(), priority = SCHEMA),
        ),
    )
}

/** 返回链路问答场景的用户提示词。 */
internal fun buildQaPrompt(
    promptComposer: PromptComposer,
    context: GraphQaContext,
    question: String,
    settings: LinkGraphSettingsState,
    requestedMode: QaMode = QaMode.AUTO,
    effectiveMode: QaMode = QaMode.AUTO,
): String = buildQaPromptPackage(
    promptComposer = promptComposer,
    context = context,
    question = question,
    settings = settings,
    requestedMode = requestedMode,
    effectiveMode = effectiveMode,
).userPrompt
