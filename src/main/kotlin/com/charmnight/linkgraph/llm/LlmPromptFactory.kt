package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.WorkbenchStep

/**
 * 把当前图上下文整理成可审计的提示词。
 * 即便暂时不接远程模型，这里也保留 promptPreview，便于用户确认输入材料。
 */
class LlmPromptFactory {
    /** 构造实现计划生成场景的提示词包。 */
    fun buildGenerationPromptPackage(
        snapshot: GenerationContext,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage {
        /** 图节点摘要列表。 */
        val nodes = snapshot.graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 图边摘要列表。 */
        val edges = snapshot.graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** Mermaid 校验问题摘要。 */
        val issues = snapshot.mermaidIssues.joinToString("\n") { issue ->
            "- [${issue.category.name}] ${issue.code}: ${issue.message}"
        }.ifBlank { "- 无" }
        /** 图差异摘要。 */
        val diff = snapshot.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
        /** 同步预览摘要。 */
        val syncPreview = snapshot.syncPreviewItems.joinToString("\n") { item ->
            "- [${item.risk.name}] ${item.title}: ${item.description}"
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的实现计划生成器。
            你的职责是基于链路图、Mermaid 问题和同步预览，输出结构化实现计划。
            只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
            即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
            计划必须面向真实代码改动，避免空泛建议。
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在根据链路图设计评审结果生成代码实现计划。
            目标模型：${settings.sanitized().model}
            
            图节点：
            $nodes
            
            图连线：
            $edges
            
            Mermaid 校验问题：
            $issues
            
            图差异：
            $diff
            
            同步预览：
            $syncPreview
            
            仅返回 JSON，结构如下：
            {
              "summary": "简短计划摘要",
              "items": [
                {
                  "id": "稳定ID",
                  "title": "需要变更的内容",
                  "description": "原因与做法",
                  "risk": "LOW|MEDIUM|HIGH",
                  "targetPath": "可选路径"
                }
              ],
              "warnings": ["可选警告"]
            }
        """.trimIndent()
        return LlmPromptPackage(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            preview = promptPreview(systemPrompt, userPrompt),
        )
    }

    /** 返回实现计划生成场景的用户提示词。 */
    fun buildGenerationPrompt(
        snapshot: GenerationContext,
        settings: LinkGraphSettingsState,
    ): String {
        return buildGenerationPromptPackage(snapshot, settings).userPrompt
    }

    /** 构造链路审计场景的提示词包。 */
    fun buildAuditPromptPackage(
        context: GraphAuditContext,
        question: String,
        settings: LinkGraphSettingsState,
        session: AuditConversationSession? = null,
    ): LlmPromptPackage {
        /** 当前审计范围内的节点。 */
        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        /** 当前审计范围内的边。 */
        val scopeEdges = GraphAuditScopeResolver.resolveScopeEdges(context, scopeNodes)
        /** 当前审计范围标签。 */
        val scopeText = if (context.selectedNodeIds.isEmpty()) {
            "整图"
        } else {
            "框选组（${context.selectedNodeIds.size} 个节点）"
        }
        /** 事实图节点摘要。 */
        val factNodes = context.factGraph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 事实图边摘要。 */
        val factEdges = context.factGraph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** 当前范围节点摘要。 */
        val selectedNodes = scopeNodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 当前范围边摘要。 */
        val selectedEdges = scopeEdges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** 草稿层节点摘要。 */
        val draftNodes = context.draftGraph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 草稿层边摘要。 */
        val draftEdges = context.draftGraph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** 历史消息摘要。 */
        val history = session?.messages?.joinToString("\n") { message ->
            "- [${message.role.name}] ${message.content}"
        }?.ifBlank { "- 无" } ?: "- 无"
        /** 已有候选变更摘要。 */
        val existingChanges = session?.candidateChanges?.joinToString("\n") { change ->
            "- ${change.changeId} | ${change.title} | before=${change.beforeState ?: "无"} | after=${change.afterState ?: "无"}"
        }?.ifBlank { "- 无" } ?: "- 无"
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的链路审计助手。
            你的职责是识别业务黑逻辑、默认兜底、运行时边界和设计遗漏，并输出对话回复与待确认候选变更。
            不允许把推测内容伪装成代码事实。
            answer 与 candidateChanges 之外，还必须输出 findings，对每条关键结论标注证据等级和引用。
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
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在做链路图审计。
            目标模型：${settings.sanitized().model}
            当前范围：$scopeText
            用户问题：$question

            当前范围节点：
            $selectedNodes

            当前范围边：
            $selectedEdges

            事实图节点：
            $factNodes

            事实图连线：
            $factEdges

            草稿层节点：
            $draftNodes

            草稿层连线：
            $draftEdges

            历史消息：
            $history

            已有待确认候选变更：
            $existingChanges

            请先给出本轮审计回答，再给出候选变更。不要把建议伪装成代码事实，也不要整表重刷已有候选项。
            仅返回 JSON，结构如下：
            {
              "answer": "审计回答",
              "findings": [
                {
                  "id": "稳定ID",
                  "claim": "一条必须可追溯的关键结论",
                  "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
                  "references": [
                    {
                      "nodeId": "可选节点ID",
                      "filePath": "可选源码路径",
                      "startLine": 1,
                      "endLine": 3
                    }
                  ]
                }
              ],
              "candidateChanges": [
                {
                  "changeId": "稳定ID",
                  "status": "PENDING_CONFIRMATION|CONFIRMED|REJECTED|SUPERSEDED",
                  "title": "候选变更标题",
                  "targetStepIds": ["可选步骤ID"],
                  "targetNodeIds": ["可选节点ID"],
                  "beforeState": "修改前状态",
                  "afterState": "修改后状态",
                  "reason": "为什么建议这样改",
                  "impactSummary": "影响摘要"
                }
              ],
              "warnings": ["可选警告"],
              "patch": null
            }
        """.trimIndent()
        return LlmPromptPackage(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            preview = promptPreview(systemPrompt, userPrompt),
        )
    }

    /** 返回链路审计场景的用户提示词。 */
    fun buildAuditPrompt(
        context: GraphAuditContext,
        question: String,
        settings: LinkGraphSettingsState,
    ): String {
        return buildAuditPromptPackage(context, question, settings).userPrompt
    }

    /** 构造差异问答场景的提示词包。 */
    fun buildDiffReviewPromptPackage(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage {
        /** 代码事实节点摘要。 */
        val factNodes = context.factGraph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 设计基线节点摘要。 */
        val designNodes = context.designBaseline.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 全量差异摘要。 */
        val diff = context.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
        /** 当前焦点差异摘要。 */
        val focusedDiffs = context.diff.entries
            .filter { entry -> entry.elementId in context.selectedDiffItemIds }
            .joinToString("\n") { entry -> diffSummary(entry) }
            .ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
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
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在做“设计图基线 vs 代码事实图”的差异审查。
            目标模型：${settings.sanitized().model}
            用户问题：$question

            当前关注差异：
            $focusedDiffs

            左侧设计基线节点：
            $designNodes

            右侧代码事实节点：
            $factNodes

            当前差异：
            $diff

            请先解释差异，再给出只写入草稿层的修订 patch 建议。不要直接修改代码事实。
            仅返回 JSON，结构如下：
            {
              "answer": "差异解释",
              "findings": [
                {
                  "id": "稳定ID",
                  "claim": "一条必须可追溯的关键结论",
                  "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
                  "references": [
                    {
                      "nodeId": "可选节点ID",
                      "filePath": "可选源码路径",
                      "startLine": 1,
                      "endLine": 3
                    }
                  ]
                }
              ],
              "warnings": ["可选警告"],
              "patch": {
                "summary": "patch 摘要",
                "operations": [
                  {
                    "id": "稳定ID",
                    "action": "ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE|ADD_ANNOTATION|MARK_UNCERTAIN",
                    "elementKind": "NODE|EDGE",
                    "elementId": "元素ID",
                    "title": "可选标题",
                    "summary": "可选摘要",
                    "metadata": {
                      "draft.claimType": "CODE_FACT|RISK_HINT|EXPLANATION_NOTE|STRUCTURAL_SUGGESTION"
                    },
                    "node": {
                      "id": "节点ID",
                      "type": "METHOD|CLASS|SQL|HTTP_ENDPOINT|FEIGN_CLIENT|DUBBO_SERVICE|MQ_TOPIC|MQ_CONSUMER|CONFIG_ITEM|XML_RESOURCE|DOC_PAGE|UNCERTAIN_LINK",
                      "title": "节点标题",
                      "doc": "可选说明",
                      "sourceTag": "DRAFT_AI"
                    }
                  }
                ],
                "addedNodeIds": [],
                "removedNodeIds": [],
                "addedEdgeIds": [],
                "removedEdgeIds": []
              }
            }
        """.trimIndent()
        return LlmPromptPackage(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            preview = promptPreview(systemPrompt, userPrompt),
        )
    }

    fun buildDiffReviewPrompt(
        context: GraphDiffContext,
        question: String,
        settings: LinkGraphSettingsState,
    ): String {
        return buildDiffReviewPromptPackage(context, question, settings).userPrompt
    }

    /** 构造代码草稿生成场景的提示词包。 */
    fun buildCodeGenerationPromptPackage(
        context: GenerationContext,
        plan: GenerationPlan?,
        settings: LinkGraphSettingsState,
    ): LlmPromptPackage {
        /** 图节点摘要列表。 */
        val nodes = context.graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 图边摘要列表。 */
        val edges = context.graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** 图差异摘要。 */
        val diff = context.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
        /** 计划项摘要。 */
        val planItems = plan?.items.orEmpty().joinToString("\n") { item ->
            "- [${item.risk.name}] ${item.title} | target=${item.targetPath ?: "未指定"} | ${item.description}"
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的代码生成器。
            你的职责是基于链路图、差异和计划项，输出可写入项目目录的代码草稿。
            只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
            即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
            每个 draft 必须给出稳定 targetPath 和完整 content。
            当信息不足时，要在 warnings 中说明，不要编造代码事实。
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在根据链路图和实现计划生成代码草稿。
            目标模型：${settings.sanitized().model}

            图节点：
            $nodes

            图连线：
            $edges

            图差异：
            $diff

            计划项：
            $planItems

            目标文件：
            ${plan?.items.orEmpty().mapNotNull { it.targetPath }.ifEmpty { listOf("未指定") }.joinToString("\n")}

            仅返回 JSON，结构如下：
            {
              "summary": "本次生成摘要",
              "warnings": ["可选警告"],
              "drafts": [
                {
                  "id": "稳定ID",
                  "sourceNodeId": "来源节点ID",
                  "title": "文件名",
                  "targetPath": "项目内相对路径",
                  "content": "完整文件内容",
                  "warnings": ["可选警告"]
                }
              ]
            }
        """.trimIndent()
        return LlmPromptPackage(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            preview = promptPreview(systemPrompt, userPrompt),
        )
    }

    /** 构造链路讲解场景的提示词包。 */
    fun buildBeautificationPromptPackage(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
        projectedSteps: List<WorkbenchStep> = emptyList(),
    ): LlmPromptPackage {
        /** 当前可见图。 */
        val graph = context.presentationContext.graph
        /** 图节点摘要列表。 */
        val nodes = graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
        /** 图边摘要列表。 */
        val edges = graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
        /** 源码片段摘要列表。 */
        val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
            sourceSnippetSummary(snippet)
        }.ifBlank { "- 无" }
        /** 当前稳定步骤摘要。 */
        val steps = projectedSteps.joinToString("\n") { step ->
            "- ${step.stepId} | ${step.kind.name} | ${step.title} | nodeRefs=${step.nodeRefs.joinToString()}"
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的步骤化链路讲解助手。
            你的职责是基于稳定步骤、链路图展示上下文和真实源码片段，补齐每一步是做什么的。
            必须围绕给定 stepId 输出步骤说明，不允许退回成 summary/sections 报告卡。
            必须优先解释当前方法内部关键流程，再补充可继续下钻的方向，不能把图上的折叠部分误写成已展示事实。
            每个步骤都必须输出 evidence 和 followUpQuestions。
            evidenceLevel 只允许：
            - DIRECT_SOURCE：直接来自当前提供的源码片段
            - DIRECT_GRAPH：直接来自当前图节点或图连线
            - CALLSITE_ONLY：当前只看到了调用点，没有看到被调实现
            - NOT_OBSERVED：当前提供的上下文没有直接观察到该行为
            只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
            即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在美化并讲解一张链路图。
            目标模型：${settings.sanitized().model}
            用户目标：${context.userGoal.ifBlank { "请提高链路图的可读性" }}
            偏好风格：${context.preferredStyle ?: "未指定"}
            讲解重点：${context.explanationFocus ?: "先讲当前方法内部，再讲跨方法扩展"}
            当前方法内部折叠节点：${context.presentationContext.hiddenCurrentMethodNodeCount}
            跨方法扩展折叠节点：${context.presentationContext.hiddenCrossMethodNodeCount}
            锚点节点：${context.presentationContext.anchorNodeId ?: "未指定"}
            当前粒度：${context.granularity.name}

            稳定步骤：
            $steps

            图节点：
            $nodes

            图连线：
            $edges

            相关源码片段：
            $sourceSnippets

            仅返回 JSON，结构如下：
            {
              "steps": [
                {
                  "stepId": "稳定ID",
                  "title": "步骤标题",
                  "description": "说明这一步在做什么",
                  "followUpQuestions": ["可继续追问的问题"],
                  "evidence": [
                    {
                      "id": "稳定ID",
                      "claim": "一条必须可追溯的关键结论",
                      "evidenceLevel": "DIRECT_SOURCE|DIRECT_GRAPH|CALLSITE_ONLY|NOT_OBSERVED",
                      "references": [
                        {
                          "nodeId": "可选节点ID",
                          "filePath": "可选源码路径",
                          "startLine": 1,
                          "endLine": 3
                        }
                      ]
                    }
                  ],
                  "downstreamTargets": ["可继续下钻的目标ID"]
                }
              ],
              "warnings": ["可选警告"]
            }
        """.trimIndent()
        return LlmPromptPackage(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            preview = promptPreview(systemPrompt, userPrompt),
        )
    }

    /** 返回链路讲解场景的用户提示词。 */
    fun buildBeautificationPrompt(
        context: GraphBeautificationContext,
        settings: LinkGraphSettingsState,
    ): String {
        return buildBeautificationPromptPackage(context, settings).userPrompt
    }

    /** 把节点转换成提示词里的单行摘要。 */
    private fun nodeSummary(node: GraphNode): String {
        /** 节点 ID 字段片段。 */
        val id = "id=${node.id} | "
        /** 节点位置字段片段。 */
        val location = node.location?.let { " @ $it" }.orEmpty()
        /** 节点签名字段片段。 */
        val signature = node.signature?.let { " | signature=$it" }.orEmpty()
        /** 节点输入字段片段。 */
        val inputs = if (node.inputs.isEmpty()) "" else " | inputs=${node.inputs.joinToString()}"
        /** 节点输出字段片段。 */
        val outputs = if (node.outputs.isEmpty()) "" else " | outputs=${node.outputs.joinToString()}"
        /** 节点文档字段片段。 */
        val doc = node.doc?.takeIf { it.isNotBlank() }?.let { " | doc=$it" }.orEmpty()
        /** 节点来源字段片段。 */
        val sourceTag = " | source=${node.sourceTag.name}"
        return "- $id[${node.type.name}] ${node.title}$location$signature$inputs$outputs$doc$sourceTag"
    }

    /** 把源码片段上下文转换成提示词里的单行摘要。 */
    private fun sourceSnippetSummary(snippet: SourceSnippetContext): String {
        return buildString {
            append("- node=")
            append(snippet.nodeId)
            append(" | path=")
            append(snippet.filePath)
            snippet.startLine?.let { append(" | startLine=").append(it) }
            snippet.endLine?.let { append(" | endLine=").append(it) }
            snippet.startOffset?.let { append(" | startOffset=").append(it) }
            snippet.endOffset?.let { append(" | endOffset=").append(it) }
            snippet.snippet?.takeIf { it.isNotBlank() }?.let { append(" | snippet=").append(it) }
        }
    }

    /** 把边转换成提示词里的单行摘要。 */
    private fun edgeSummary(edge: GraphEdge): String {
        /** 边标签字段片段。 */
        val label = edge.label?.let { " | label=$it" }.orEmpty()
        return "- [${edge.type.name}] ${edge.fromNodeId} -> ${edge.toNodeId}$label"
    }

    /** 把差异条目转换成提示词里的单行摘要。 */
    private fun diffSummary(entry: GraphDiffEntry): String {
        /** 差异字段片段。 */
        val fields = if (entry.fields.isEmpty()) "" else " | fields=${entry.fields.joinToString()}"
        /** 差异说明片段。 */
        val message = entry.message?.let { " | $it" }.orEmpty()
        return "- [${entry.status.name}] ${entry.elementKind.name}:${entry.elementId}$fields$message"
    }

    /** 拼装用于前端展示的 prompt 预览文本。 */
    private fun promptPreview(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        return """
            [system]
            $systemPrompt

            [user]
            $userPrompt
        """.trimIndent()
    }
}
