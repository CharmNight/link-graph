package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AuditConversationSession
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
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
        /** 已确认草稿变更摘要。 */
        val confirmedChanges = snapshot.confirmedChanges
            .joinToString("\n") { change -> confirmedChangeSummary(change, snapshot.graph) }
            .ifBlank { "- 无" }
        /** 真实源码片段摘要。 */
        val sourceSnippets = snapshot.sourceContext.joinToString("\n") { snippet ->
            sourceSnippetSummary(snippet)
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的实现计划生成器。
            你的职责是基于链路图、已确认草稿变更、真实源码片段、Mermaid 问题和同步预览，输出结构化实现计划。
            已确认草稿变更代表用户已经确认要改的真实目标，你必须优先围绕这些确认项生成计划，不要被无关图节点带偏。
            下方“相关源码片段”来自当前项目的真实源码；如果某个目标已经给出对应片段，禁止声称未提供源码上下文。
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

            已确认草稿变更：
            $confirmedChanges

            相关源码片段：
            $sourceSnippets
            
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
        /** 已有风险线索摘要。 */
        val existingInvestigationLeads = session?.investigationLeads?.joinToString("\n") { lead ->
            "- ${lead.leadId} | ${lead.title} | gap=${lead.evidenceGap.ifBlank { "未标注" }} | next=${lead.recommendedQuestion.ifBlank { "未标注" }}"
        }?.ifBlank { "- 无" } ?: "- 无"
        /** 真实源码片段摘要。 */
        val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
            sourceSnippetSummary(snippet)
        }.ifBlank { "- 无" }
        /** 本轮取证轨迹摘要。 */
        val evidenceTrace = context.evidenceTrace.joinToString("\n") { trace ->
            buildString {
                append("- node=").append(trace.nodeId)
                append(" | path=").append(trace.filePath)
                trace.startLine?.let { append(" | startLine=").append(it) }
                trace.endLine?.let { append(" | endLine=").append(it) }
                append(" | reason=").append(trace.reason)
                append(" | includedInPrompt=").append(trace.includedInPrompt)
            }
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的链路审计助手。
            你的职责是识别业务黑逻辑、默认兜底、运行时边界和设计遗漏，并输出对话回复、待确认候选变更以及风险线索。
            你的第一优先级是直接回答“用户问题”，不要绕开问题泛化输出通用审计结论。
            如果用户问题是在“介绍 / 解释 / 讲解链路”，answer 必须先解释链路本身，不要输出无关风险建议。
            只有当用户问题明确要求审计、找问题、调整逻辑，或者你发现了与用户问题直接相关且证据充分的缺陷时，才允许输出 candidateChanges；否则 candidateChanges 必须返回 []。
            candidateChanges[*] 必须绑定到 findings 中的 supportingFindingIds；如果没有可追溯 findings，就不要输出这条 candidateChange。
            investigationLeads 用来表达“怀疑点 / 需要继续取证的线索”，它们不能冒充已经确认的变更，也不能写成草稿结论。
            如果证据等级只有 CALLSITE_ONLY 或 NOT_OBSERVED，就不要输出 candidateChanges，改为输出 investigationLeads。
            investigationLeads[*] 也必须绑定到 findings 中的 supportingFindingIds；如果没有可追溯 findings，就不要输出这条 investigationLead。
            禁止输出与用户问题无关的通用安全、性能、规范性建议。
            不允许把推测内容伪装成代码事实。
            answer、candidateChanges、investigationLeads 之外，还必须输出 findings，对每条关键结论标注证据等级和引用。
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

            相关源码片段：
            $sourceSnippets

            本轮取证轨迹：
            $evidenceTrace

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

            已有风险线索：
            $existingInvestigationLeads

            请逐条对照“用户问题”回答。
            如果当前上下文不足以回答用户问题，answer 必须明确说明“当前证据不足以回答该问题”，不要转而输出无关建议。
            你的第一优先级是直接回答“用户问题”。
            禁止输出与用户问题无关的通用安全、性能、规范性建议。
            candidateChanges 只允许保留与“用户问题”直接相关、且已经有 DIRECT_SOURCE / DIRECT_GRAPH 支撑的修改建议；如果当前轮只是解释链路或回答事实问题，请返回 []。
            investigationLeads 用来承接证据不足但值得继续追问的线索；它们必须明确写出“已观察到什么、还缺什么、下一轮建议问什么”。
            请先给出本轮审计回答，再给出 candidateChanges 与 investigationLeads。不要把建议伪装成代码事实，也不要整表重刷已有候选项。
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
                  "claimType": "CODE_FACT|RISK_HINT|EXPLANATION_NOTE|STRUCTURAL_SUGGESTION",
                  "title": "候选变更标题",
                  "targetStepIds": ["可选步骤ID"],
                  "targetNodeIds": ["可选节点ID"],
                  "beforeState": "修改前状态",
                  "afterState": "修改后状态",
                  "reason": "为什么建议这样改",
                  "impactSummary": "影响摘要",
                  "supportingFindingIds": ["必须对应 findings[*].id"]
                }
              ],
              "investigationLeads": [
                {
                  "leadId": "稳定ID",
                  "status": "OPEN|PROMOTED|DISMISSED|SUPERSEDED",
                  "claimType": "RISK_HINT|STRUCTURAL_SUGGESTION",
                  "title": "风险线索标题",
                  "targetStepIds": ["可选步骤ID"],
                  "targetNodeIds": ["可选节点ID"],
                  "summary": "当前已经观察到什么",
                  "evidenceGap": "还缺什么证据",
                  "recommendedQuestion": "下一轮建议追问什么",
                  "supportingFindingIds": ["必须对应 findings[*].id"]
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
            "- [${item.risk.name}] ${item.title} | target=${item.targetPath ?: "未指定"} | scopes=${item.editScopes.size} | ${item.description}"
        }.ifBlank { "- 无" }
        /** 计划中带出的精确 scope 详情。 */
        val planScopeDetails = plan?.items.orEmpty()
            .flatMap { item ->
                item.editScopes.map { scope ->
                    buildString {
                        append("- planItem=").append(item.id)
                        append(" | scopeId=").append(scope.scopeId)
                        append(" | filePath=").append(scope.filePath)
                        append(" | symbolKind=").append(scope.symbolKind)
                        scope.symbolSignature?.let { append(" | symbolSignature=").append(it) }
                        scope.startLine?.let { append(" | startLine=").append(it) }
                        scope.endLine?.let { append(" | endLine=").append(it) }
                        append(" | allowedChangeKinds=").append(scope.allowedChangeKinds.joinToString(", "))
                    }
                }
            }
            .let { details -> details.ifEmpty { listOf("- 无") } }
            .joinToString("\n")
        /** 已确认草稿变更携带的 scope 详情。 */
        val confirmedChangeScopeDetails = context.confirmedChanges
            .flatMap { change ->
                change.editScopes.map { scope ->
                    buildString {
                        append("- change=").append(change.sourceChangeId ?: change.entryId)
                        append(" | scopeId=").append(scope.scopeId)
                        append(" | filePath=").append(scope.filePath)
                        append(" | symbolKind=").append(scope.symbolKind)
                        scope.symbolSignature?.let { append(" | symbolSignature=").append(it) }
                        scope.startLine?.let { append(" | startLine=").append(it) }
                        scope.endLine?.let { append(" | endLine=").append(it) }
                        append(" | allowedChangeKinds=").append(scope.allowedChangeKinds.joinToString(", "))
                    }
                }
            }
            .ifEmpty { listOf("- 无") }
            .joinToString("\n")
        /** 已确认草稿变更摘要。 */
        val confirmedChanges = context.confirmedChanges
            .joinToString("\n") { change -> confirmedChangeSummary(change, context.graph) }
            .ifBlank { "- 无" }
        /** 真实源码片段摘要。 */
        val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
            sourceSnippetSummary(snippet)
        }.ifBlank { "- 无" }
        /** 面向模型的系统提示词。 */
        val systemPrompt = """
            你是 IDEA Link Graph 的代码生成器。
            你的职责是基于链路图、已确认草稿变更、真实源码片段、差异和计划项，输出可写入项目目录的代码草稿。
            如果已确认草稿变更已经明确了要改的现有方法或文件，你必须优先围绕这些确认项生成可落地的代码，而不是只生成新增类壳子。
            下方“相关源码片段”来自当前项目的真实源码；如果某个目标已经给出对应片段，禁止声称未提供源码上下文。
            对于已经存在的目标文件，禁止返回整文件 content；你必须返回 editOperations，并且每条 operation 都要绑定到已给定的 edit scope。
            你只能使用 scope.allowedChangeKinds 明确授权过的 operation kind，禁止超出 scope 授权范围自行扩展。
            对于已经存在的目标文件，你必须保留目标文件中与本次变更无关的现有代码，只修改与确认项直接相关的方法、字段、import 和注释。
            如果目标已经指向现有 Java 文件，生成结果必须继续沿用原有包名、类型名和未提及成员，不允许把整文件改写成无关的新骨架。
            Java existing-file 可用 operation kind：REPLACE_METHOD_BLOCK、REPLACE_METHOD_BODY、ADD_IMPORT、ADD_FIELD、INSERT_METHOD_AFTER。
            Kotlin existing-file 可用 operation kind：REPLACE_METHOD_BLOCK、REPLACE_METHOD_BODY。
            只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
            即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
            新文件 draft 才允许返回完整 content。
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

            已确认草稿变更：
            $confirmedChanges

            已确认草稿变更附带的 edit scopes：
            $confirmedChangeScopeDetails

            相关源码片段：
            $sourceSnippets

            计划项：
            $planItems

            已授权 edit scopes：
            $planScopeDetails

            目标文件：
            ${plan?.items.orEmpty().mapNotNull { it.targetPath }.ifEmpty { listOf("未指定") }.joinToString("\n")}

            如果目标文件已经明确指向现有源码，请返回结构化 editOperations，而不是整文件内容。
            operation.kind 必须严格从对应 scope.allowedChangeKinds 中选择；如果 scope 没授权，就不要生成该 operation。
            保留与本次确认项无关的现有逻辑、成员、注释和 import，不要删除未提及的成员，也不要凭空改名或迁移到别的文件。

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
                  "content": "仅 CREATE_FILE 时返回完整文件内容",
                  "editOperations": [
                    {
                      "operationId": "稳定ID",
                      "filePath": "项目内相对路径",
                      "scopeId": "必须对应既有 edit scope",
                      "kind": "REPLACE_METHOD_BLOCK|REPLACE_METHOD_BODY|INSERT_METHOD_AFTER|ADD_IMPORT|ADD_FIELD|CREATE_FILE",
                      "payload": "结构化操作负载",
                      "warnings": ["可选警告"]
                    }
                  ],
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
        /** 当前追问上下文。 */
        val followUp = context.followUp
        /** 面向模型的追问说明块。 */
        val followUpBlock = followUp?.let { current ->
            """
            讲解模式：追问讲解
            追问上下文：
            - 当前步骤ID：${current.stepId}
            - 当前步骤标题：${current.stepTitle}
            - 用户追问：${current.question}
            本轮回答必须先直接回答用户追问，再补充代码位置、关键条件/分支和下一跳方法。
            steps[0] 必须优先对应当前步骤；description 的首句必须先回答用户追问。
            如果当前证据不足，必须明确写出“不足以确认”，不要编造隐藏逻辑。
            """.trimIndent()
        } ?: """
            讲解模式：常规讲解
            讲解重点：${context.explanationFocus ?: "先讲当前方法内部，再讲跨方法扩展"}
            """.trimIndent()
        /** 面向模型的系统提示词。 */
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
        """.trimIndent()
        /** 面向模型的用户提示词。 */
        val userPrompt = """
            你正在美化并讲解一张链路图。
            目标模型：${settings.sanitized().model}
            用户目标：${context.userGoal.ifBlank { "请提高链路图的可读性" }}
            偏好风格：${context.preferredStyle ?: "未指定"}
            $followUpBlock
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

    /** 把已确认草稿变更转换成提示词里的单行摘要。 */
    private fun confirmedChangeSummary(
        change: DraftWorkbenchEntry,
        graph: com.charmnight.linkgraph.model.GraphDocument,
    ): String {
        val nodeById = graph.nodes.associateBy { it.id }
        val targets = change.targetNodeIds.joinToString("; ").ifBlank { "未指定节点" }
        val targetFiles = change.targetNodeIds.mapNotNull { nodeId ->
            nodeById[nodeId]?.metadata?.get("source.filePath")
                ?: nodeById[nodeId]?.location?.substringBefore(':')
        }.distinct().ifEmpty { listOf("未指定文件") }
        val before = change.beforeState?.takeIf { it.isNotBlank() } ?: "无"
        val after = change.afterState?.takeIf { it.isNotBlank() } ?: "无"
        val reason = change.reason.ifBlank { "无" }
        val impact = change.impactSummary.takeIf { it.isNotBlank() } ?: "无"
        val claimType = change.claimType ?: "未标注"
        val evidenceLevels = change.evidence.map { it.evidenceLevel.name }.distinct().ifEmpty { listOf("未标注") }
        return "- ${change.sourceChangeId ?: change.entryId} | ${change.title} | targets=$targets | files=${targetFiles.joinToString()} | before=$before | after=$after | reason=$reason | impact=$impact | claimType=$claimType | evidence=${evidenceLevels.joinToString()}"
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
