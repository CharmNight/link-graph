package com.charmnight.linkgraph.llm.qa

import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.GraphQaScopeResolver
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.ResultEvidenceLevel
import com.charmnight.linkgraph.llm.ResultEvidenceReference
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.markRuntimeEvidenceTrusted
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.InvestigationThreadStatus
import com.charmnight.linkgraph.workbench.QaMode

/**
 * 构造不依赖远程模型的本地问答结果（P2-1 深度重构）。
 *
 * 把 buildMockResult 及其辅助方法（buildMockDirectSourceFindings /
 * resolveMockDirectSourceTargets / buildMockCandidateTitle）封装为独立 class，
 * 让 GraphQaPatchService 只持有 LocalRuleBuilder 实例并 delegate。
 *
 * 输出是不含 conversation turn 的 GraphPatchResult；调用方负责走 applyConversationTurn
 * 把结果写入会话。
 */
internal class QaPatchLocalRuleBuilder {
    /** 构造本地规则化场景的 GraphPatchResult（未经 conversation turn 处理）。 */
    fun build(
        context: GraphQaContext,
        question: String,
        prompt: String,
        sourceThreadId: String? = null,
        requestedMode: QaMode = QaMode.AUTO,
        effectiveMode: QaMode = QaMode.AUTO,
        runtimeEvidenceTrusted: Boolean = false,
    ): GraphPatchResult {
        val scopeNodes = GraphQaScopeResolver.resolveScopeNodes(context)
        val analysisGraph = context.editableGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: context.factGraph
        val hasFallbackIntent = question.contains("兜底") || question.contains("默认")
        val explanationIntent = question.contains("介绍") || question.contains("解释") || question.contains("讲解")
        val explicitQaIntent = question.contains("复核")
            || question.contains("问题")
            || question.contains("风险")
            || question.contains("漏洞")
            || question.contains("遗漏")
            || question.contains("修改")
            || question.contains("调整")
            || question.contains("修正")
        val scopeKey = scopeNodes.map(GraphNode::id).sorted().joinToString(",").ifBlank { "scope" }
        val scopeLabel = when {
            context.selectedNodeIds.isEmpty() -> "整图"
            scopeNodes.size > 1 -> "当前框选范围（${scopeNodes.size} 个节点）"
            else -> "当前节点"
        }
        val directSourceFindings = buildMockDirectSourceFindings(context)
        val directSourceTargets = resolveMockDirectSourceTargets(context, scopeNodes, analysisGraph)
        val hasLocalRuleChangeHint = (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
            questionExplicitlyRequestsChange(question) &&
            directSourceFindings.isNotEmpty() &&
            directSourceTargets.isNotEmpty()
        val explanationAnswer = buildString {
            append("当前范围说明：").append(scopeLabel).append("。")
            if (scopeNodes.isNotEmpty()) {
                append("本轮主要涉及：")
                append(scopeNodes.joinToString(" -> ") { it.title.ifBlank { it.id } })
                append("。")
            }
            if (analysisGraph.edges.isNotEmpty()) {
                append("当前看到的调用/连接数量为 ").append(analysisGraph.edges.size).append("。")
            }
        }
        val answer = if (effectiveMode == QaMode.ANSWER) {
            explanationAnswer.ifBlank {
                "当前轮结论：当前证据不足以完整回答该问题；本轮不会生成候选变更或风险线程。"
            }
        } else if (hasLocalRuleChangeHint) {
            """
            当前轮结论：本地规则在 $scopeLabel 已直接观察到可落点的源码证据，但不会生成待确认变更。
            处理建议：先登记为风险线索；需要可确认变更时，请使用远程模型或 runtime 证据链生成结构化候选变更。
            """.trimIndent()
        } else if (explanationIntent && !explicitQaIntent && !hasFallbackIntent) {
            explanationAnswer
        } else if (hasFallbackIntent) {
            """
            当前轮结论：$scopeLabel 里存在待确认边界，应先登记为“默认兜底规则”风险线索。
            处理建议：先确认条件未命中时的处理分支，拿到直接证据后再决定是否写入草稿层。
            """.trimIndent()
        } else {
            """
            当前轮结论：$scopeLabel 里存在待确认业务规则，应先登记为风险线索而不是直接写草稿。
            处理建议：先确认真实业务约束，拿到直接证据后再决定是否写入草稿层。
            """.trimIndent()
        }
        val findings = if (hasLocalRuleChangeHint) {
            directSourceFindings
        } else {
            val findingClaim = if (explanationIntent && !explicitQaIntent && !hasFallbackIntent) {
                "当前图里可以直接观察到该链路范围内的节点与连接关系。"
            } else if (hasFallbackIntent) {
                "当前上下文没有直接观察到默认兜底分支。"
            } else {
                "当前上下文没有直接观察到足以证明完整业务规则的证据。"
            }
            scopeNodes.ifEmpty { analysisGraph.nodes.take(1) }
                .distinctBy(GraphNode::id)
                .mapIndexed { index, node ->
                    ResultEvidenceFinding(
                        id = "qa-finding-$index",
                        claim = findingClaim,
                        evidenceLevel = ResultEvidenceLevel.NOT_OBSERVED,
                        references = listOf(ResultEvidenceReference(nodeId = node.id)),
                    )
                }
        }
        val candidateChanges = if (hasLocalRuleChangeHint && runtimeEvidenceTrusted) {
            listOf(
                CandidateDraftChange(
                    changeId = GraphNode.stableId(NodeType.DOC_PAGE, directSourceTargets.joinToString(",") { it.id }, "runtime-candidate-change"),
                    status = CandidateDraftChangeStatus.PENDING_CONFIRMATION,
                    title = buildMockCandidateTitle(question, directSourceTargets),
                    targetNodeIds = directSourceTargets.map(GraphNode::id),
                    reason = "runtime 已读取直接源码证据并锚定到本轮修改请求涉及的位置。",
                    impactSummary = "已具备 runtime 代码证据，可继续进入精确代码 diff 生成。",
                    claimType = "CODE_FACT",
                    evidence = findings,
                ),
            )
        } else {
            emptyList()
        }
        val investigationThreads = if (
            effectiveMode == QaMode.ANSWER ||
            (hasLocalRuleChangeHint && runtimeEvidenceTrusted) ||
            (explanationIntent && !explicitQaIntent && !hasFallbackIntent)
        ) {
            emptyList()
        } else {
            listOf(
                InvestigationThread(
                    threadId = GraphNode.stableId(NodeType.DOC_PAGE, "$scopeKey-qa-change", "qa-thread"),
                    status = InvestigationThreadStatus.OPEN,
                    title = if (hasLocalRuleChangeHint) {
                        buildMockCandidateTitle(question, directSourceTargets)
                    } else if (hasFallbackIntent) {
                        "补充默认兜底规则"
                    } else {
                        "补充业务规则说明"
                    },
                    targetNodeIds = scopeNodes.ifEmpty { analysisGraph.nodes.take(1) }.map(GraphNode::id),
                    summary = if (hasLocalRuleChangeHint) {
                        "本地规则只确认当前源码片段与修改请求相关，不能直接生成待确认变更。"
                    } else if (hasFallbackIntent) {
                        "当前还不能证明默认兜底逻辑存在或不存在，需要继续核对条件未命中时的处理分支。"
                    } else {
                        "当前还不能证明这条业务规则真实存在，需要继续核对相关源码或图节点。"
                    },
                    evidenceGap = if (hasLocalRuleChangeHint) {
                        "缺少远程模型或 runtime 结构化候选变更结果。"
                    } else if (hasFallbackIntent) {
                        "目前没有直接看到条件未命中后的处理分支。"
                    } else {
                        "目前没有直接看到足以证明完整业务规则的源码或图事实。"
                    },
                    recommendedQuestion = if (hasLocalRuleChangeHint) {
                        "请基于当前直接源码证据生成结构化候选变更，并通过本地 edit scope 校验。"
                    } else if (hasFallbackIntent) {
                        "请继续取证：定位条件未命中时的默认处理分支，确认是否存在明确兜底逻辑。"
                    } else {
                        "请继续取证：定位这条链路对应的真实业务规则实现，确认当前图里缺失的是哪一段源码或分支。"
                    },
                    claimType = "RISK_HINT",
                    evidence = findings,
                ),
            )
        }
        val baseResult = GraphPatchResult(
            source = LlmResultSource.LOCAL_RULE,
            question = question,
            requestedMode = requestedMode,
            effectiveMode = effectiveMode,
            answer = answer,
            promptPreview = prompt,
            findings = findings,
            candidateChanges = candidateChanges,
            investigationThreads = investigationThreads,
            sourceContext = context.sourceContext,
            evidenceTrace = context.evidenceTrace,
        )
        return if (runtimeEvidenceTrusted) baseResult.markRuntimeEvidenceTrusted() else baseResult
    }

    /** 把问答上下文中已有的源码片段转换为直接源码证据结论。 */
    private fun buildMockDirectSourceFindings(
        context: GraphQaContext,
    ): List<ResultEvidenceFinding> =
        context.sourceContext
            .distinctBy { snippet -> "${snippet.nodeId}:${snippet.filePath}:${snippet.startLine}:${snippet.endLine}" }
            .mapIndexed { index, snippet ->
                ResultEvidenceFinding(
                    id = "qa-direct-source-$index",
                    claim = "当前源码片段里已经直接定位到本轮修改请求涉及的实现位置。",
                    evidenceLevel = ResultEvidenceLevel.DIRECT_SOURCE,
                    references = listOf(
                        ResultEvidenceReference(
                            nodeId = snippet.nodeId,
                            filePath = snippet.filePath,
                            startLine = snippet.startLine,
                            endLine = snippet.endLine,
                        ),
                    ),
                )
            }

    /** 解析本地规则化场景下候选变更应当落到的目标节点列表。 */
    private fun resolveMockDirectSourceTargets(
        context: GraphQaContext,
        scopeNodes: List<GraphNode>,
        analysisGraph: GraphDocument,
    ): List<GraphNode> {
        val nodeById = analysisGraph.nodes.associateBy(GraphNode::id)
        val preferredNodeIds = (
            scopeNodes.map(GraphNode::id) +
                context.sourceContext.map(SourceSnippetContext::nodeId)
            ).distinct()
        return preferredNodeIds.mapNotNull(nodeById::get).ifEmpty {
            analysisGraph.nodes.take(1)
        }
    }

    /** 根据用户问题或目标节点标题生成本地规则化候选变更的标题。 */
    private fun buildMockCandidateTitle(
        question: String,
        targetNodes: List<GraphNode>,
    ): String {
        val normalizedQuestion = question.trim().removeSuffix("。")
        val trimmedQuestion = normalizedQuestion.removePrefix("请").trim()
        if (trimmedQuestion.isNotBlank()) {
            return trimmedQuestion
        }
        return targetNodes.joinToString("、") { it.title.ifBlank { it.id } }
    }
}
