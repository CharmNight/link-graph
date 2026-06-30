package com.charmnight.linkgraph.llm.qa

import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.TrustedEditScopePathResolver
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftDiagnostics
import com.charmnight.linkgraph.workbench.CandidateGraphPatchComposer
import com.charmnight.linkgraph.workbench.InvestigationThread
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.hasDirectEvidence
import com.intellij.openapi.diagnostic.Logger

/**
 * 把问答候选变更与风险线程按证据强度分类的协调器（P2-1 深度重构）。
 *
 * 之前 classifyQaOutputs / normalizeCandidateChanges / deriveEditScopes /
 * promoteThreadsToCandidateChanges 4 个方法散在 GraphQaPatchService 内，
 * 共享 candidatePatchComposer / trustedEditScopePathResolver / traceEnabled / logger
 * 4 项实例状态。本类把这 4 项状态包成 QaPatchClassifier，让 GraphQaPatchService
 * 只持有一个 classifier 实例并 delegate，主类只剩"调度 + 事件"职责。
 */
internal class QaPatchClassifier(
    private val candidatePatchComposer: CandidateGraphPatchComposer,
    private val trustedEditScopePathResolver: TrustedEditScopePathResolver,
    private val traceEnabled: Boolean,
    private val logger: Logger,
) {
    /** 分类结果：保留下来的候选变更 + 派生 / 透传的风险线程。 */
    data class Result(
        val candidateChanges: List<CandidateDraftChange>,
        val investigationThreads: List<InvestigationThread>,
    )

    /**
     * 把候选变更与风险线程按证据强度分类：直接证据充足的提升为待确认项，证据不足的降级为风险线程。
     *
     * 流程：
     * 1. 受 LOCAL_RULE 限制时跳过候选变更；否则走 normalizeCandidateChanges
     * 2. 直接证据充足 → 保留为待确认项 + 派生 editScopes
     * 3. 证据不足 → 降级为风险线程
     * 4. CHANGE / AUTO 模式且候选为空且问题明确要求修改时，把 OPEN 线程提升为候选
     * 5. 按 effectiveMode / sourceThreadId 过滤风险线程
     */
    fun classify(
        candidateChanges: List<CandidateDraftChange>,
        explicitInvestigationThreads: List<InvestigationThread>,
        context: GraphQaContext,
        question: String,
        source: LlmResultSource,
        runtimeEvidenceTrusted: Boolean,
        effectiveMode: QaMode,
        sourceThreadId: String?,
    ): Result {
        val promotableChanges = mutableListOf<CandidateDraftChange>()
        val investigationThreads = linkedMapOf<String, InvestigationThread>()

        val candidateInput = if (
            canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted) &&
            (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO)
        ) {
            candidateChanges
        } else {
            emptyList()
        }
        normalizeCandidateChanges(candidateInput, context).forEach { change ->
            if (change.hasDirectEvidence()) {
                if (traceEnabled) {
                    logger.warn("问答候选变更保留为待确认项: ${CandidateDraftDiagnostics.summarizeCandidateChange(change)}")
                }
                promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
            } else {
                val thread = threadFromWeakCandidateChange(change)
                if (traceEnabled) {
                    logger.warn(
                        "问答候选变更降级为线索: ${CandidateDraftDiagnostics.summarizeCandidateChange(change)}, " +
                            "threadId=${thread.threadId}, strongestEvidence=${change.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE"}",
                    )
                }
                investigationThreads[thread.threadId] = thread
            }
        }
        val normalizedInvestigationThreads = normalizeInvestigationThreads(explicitInvestigationThreads)
        if (
            canUseConfirmableCandidatePath(source, runtimeEvidenceTrusted) &&
            (effectiveMode == QaMode.CHANGE || effectiveMode == QaMode.AUTO) &&
            promotableChanges.isEmpty() &&
            questionExplicitlyRequestsChange(question)
        ) {
            normalizedInvestigationThreads
                .filter(::isEligibleForCandidatePromotion)
                .map(::candidateFromThread)
                .let { promoted -> normalizeCandidateChanges(promoted, context) }
                .filter { change -> change.hasDirectEvidence() }
                .forEach { change ->
                    if (traceEnabled) {
                        logger.warn(
                            "问答风险线程提升为待确认项: threadBackfill=${change.changeId}, " +
                                "question=${question.trim()}, " +
                                "candidate=${CandidateDraftDiagnostics.summarizeCandidateChange(change)}",
                        )
                    }
                    promotableChanges += change.copy(editScopes = deriveEditScopes(change, context))
                }
        }
        normalizedInvestigationThreads
            .filter { thread -> effectiveMode != QaMode.ANSWER }
            .filter { thread -> effectiveMode != QaMode.INVESTIGATE || sourceThreadId == null || thread.threadId == sourceThreadId }
            .forEach { thread ->
                investigationThreads[thread.threadId] = thread
            }

        return Result(
            candidateChanges = promotableChanges,
            investigationThreads = investigationThreads.values.toList(),
        )
    }

    /**
     * 归一化候选变更列表：去重证据、丢弃无证据项、补充 claimType 与 editScopes 等。
     */
    private fun normalizeCandidateChanges(
        changes: List<CandidateDraftChange>,
        context: GraphQaContext,
    ): List<CandidateDraftChange> {
        val candidateBaseGraph = GraphDocument(
            nodes = (context.editableGraph.nodes + context.factGraph.nodes).distinctBy(GraphNode::id),
            edges = (context.editableGraph.edges + context.factGraph.edges).distinctBy(GraphEdge::id),
        )
        return changes.mapNotNull { change ->
            val normalizedEvidence = change.evidence.distinctBy(ResultEvidenceFinding::id)
            if (normalizedEvidence.isEmpty()) {
                if (traceEnabled) {
                    logger.warn("问答候选变更被丢弃: changeId=${change.changeId}, reason=empty-evidence")
                }
                return@mapNotNull null
            }
            val normalizedCandidate = candidatePatchComposer.normalizeCandidate(
                candidate = change.copy(
                    claimType = change.claimType ?: inferClaimType(normalizedEvidence),
                    evidence = normalizedEvidence,
                    editScopes = change.editScopes.distinctBy(EditScope::scopeId),
                ),
                baseGraph = candidateBaseGraph,
            )
            if (traceEnabled) {
                logger.warn(
                    "问答候选变更完成归一化: ${CandidateDraftDiagnostics.summarizeCandidateChange(normalizedCandidate)}, " +
                        "directEvidence=${normalizedCandidate.hasDirectEvidence()}",
                )
            }
            normalizedCandidate
        }
    }

    /** 按节点 + 源码片段 + 证据引用派生 edit scopes，绑定到候选变更的目标节点。 */
    private fun deriveEditScopes(
        change: CandidateDraftChange,
        context: GraphQaContext,
    ): List<EditScope> {
        val nodeById = (context.editableGraph.nodes + context.factGraph.nodes).distinctBy(GraphNode::id).associateBy(GraphNode::id)
        val sourceSnippetByNodeId = context.sourceContext.associateBy { it.nodeId }
        val supportingFindingIds = change.evidence.map(ResultEvidenceFinding::id)
        return change.targetNodeIds.mapNotNull { nodeId ->
            val node = nodeById[nodeId] ?: return@mapNotNull null
            val directReference = change.evidence.firstNotNullOfOrNull { finding ->
                finding.references.firstOrNull { reference ->
                    reference.nodeId == null || reference.nodeId == nodeId
                }
            }
            val snippet = sourceSnippetByNodeId[nodeId]
            val location = trustedEditScopePathResolver.resolve(
                node = node,
                snippet = snippet,
                reference = directReference,
            ) ?: return@mapNotNull null
            EditScope(
                scopeId = "scope-${change.changeId}-$nodeId",
                targetNodeId = nodeId,
                filePath = location.filePath,
                language = inferLanguage(location.filePath),
                symbolKind = node.type.name,
                symbolSignature = editableSymbolSignature(node),
                startOffset = location.startOffset,
                endOffset = location.endOffset,
                startLine = location.startLine,
                endLine = location.endLine,
                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK", "REPLACE_METHOD_BODY", "ADD_IMPORT"),
                supportingFindingIds = supportingFindingIds,
            )
        }.distinctBy(EditScope::scopeId)
    }
}
