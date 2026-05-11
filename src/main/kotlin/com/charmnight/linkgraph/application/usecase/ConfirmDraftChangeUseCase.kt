package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.isEligibleForDraftConfirmation

sealed interface ConfirmDraftChangeUseCaseResult {
    data object MissingCandidate : ConfirmDraftChangeUseCaseResult

    data class Rejected(
        val reason: String,
    ) : ConfirmDraftChangeUseCaseResult

    data class Confirmed(
        val candidate: CandidateDraftChange,
        val observedNodeIds: List<String>,
        val confirmedEntry: DraftWorkbenchEntry?,
        val draftState: DraftWorkbenchState,
        val rebuiltGraph: GraphDocument,
        val updatedAuditResult: GraphPatchResult,
    ) : ConfirmDraftChangeUseCaseResult
}

sealed interface UnconfirmDraftChangeUseCaseResult {
    data object MissingEntry : UnconfirmDraftChangeUseCaseResult

    data class Unconfirmed(
        val removedEntry: DraftWorkbenchEntry,
        val draftState: DraftWorkbenchState,
        val rebuiltGraph: GraphDocument,
        val updatedAuditResult: GraphPatchResult,
    ) : UnconfirmDraftChangeUseCaseResult
}

class ConfirmDraftChangeUseCase(
    private val draftWorkbenchService: DraftWorkbenchService,
    private val graphPatchApplyService: GraphPatchApplyService,
) {
    fun confirm(
        snapshot: ApplicationSnapshot,
        auditResult: GraphPatchResult,
        changeId: String,
    ): ConfirmDraftChangeUseCaseResult {
        val candidate = auditResult.candidateChanges.firstOrNull { it.changeId == changeId }
            ?: return ConfirmDraftChangeUseCaseResult.MissingCandidate
        val baseGraph = snapshot.workspaceGraph
        if (!candidate.isEligibleForDraftConfirmation()) {
            return ConfirmDraftChangeUseCaseResult.Rejected("当前候选变更缺少直接证据，不能直接写入草稿层。")
        }
        if (!candidateBelongsToSelectedMethod(candidate, snapshot.selectedMethodSignature, baseGraph)) {
            return ConfirmDraftChangeUseCaseResult.Rejected("当前候选变更不属于当前选中的方法，不能直接写入草稿层。")
        }
        val confirmation = draftWorkbenchService.confirmCandidateChange(
            draft = snapshot.draftWorkbenchState,
            candidate = candidate,
            baseGraph = baseGraph,
        )
        if (confirmation.failureReason != null) {
            return ConfirmDraftChangeUseCaseResult.Rejected(confirmation.failureReason)
        }
        val confirmedEntry = confirmation.draftChanges.lastOrNull()
        return ConfirmDraftChangeUseCaseResult.Confirmed(
            candidate = candidate,
            observedNodeIds = observedNodeIds(candidate),
            confirmedEntry = confirmedEntry,
            draftState = confirmation.draftState,
            rebuiltGraph = rebuildConfirmedDraftGraph(snapshot, confirmation.draftState),
            updatedAuditResult = updateAuditResultForConfirmation(auditResult, changeId, confirmedEntry),
        )
    }

    fun unconfirm(
        snapshot: ApplicationSnapshot,
        auditResult: GraphPatchResult,
        changeId: String,
    ): UnconfirmDraftChangeUseCaseResult {
        val removal = draftWorkbenchService.unconfirmCandidateChange(snapshot.draftWorkbenchState, changeId)
        val removedEntry = removal.removedEntry ?: return UnconfirmDraftChangeUseCaseResult.MissingEntry
        return UnconfirmDraftChangeUseCaseResult.Unconfirmed(
            removedEntry = removedEntry,
            draftState = removal.draftState,
            rebuiltGraph = rebuildConfirmedDraftGraph(snapshot, removal.draftState),
            updatedAuditResult = updateAuditResultForUnconfirmation(auditResult, changeId),
        )
    }

    private fun rebuildConfirmedDraftGraph(
        snapshot: ApplicationSnapshot,
        draftState: DraftWorkbenchState,
    ): GraphDocument {
        val baseGraph = snapshot.workspaceBaseGraph
        return draftState.draftChanges.fold(baseGraph) { currentGraph, entry ->
            val patch = entry.graphPatch ?: return@fold currentGraph
            graphPatchApplyService.apply(currentGraph, patch)
        }
    }

    private fun observedNodeIds(candidate: CandidateDraftChange): List<String> {
        val nodeIds = linkedSetOf<String>()
        nodeIds += candidate.targetNodeIds
        nodeIds += candidate.graphPatch?.operations
            ?.mapNotNull { operation -> operation.node?.id?.takeIf(String::isNotBlank) ?: operation.elementId.takeIf(String::isNotBlank) }
            .orEmpty()
        candidate.evidence
            .flatMap { finding -> finding.references }
            .mapNotNullTo(nodeIds) { reference -> reference.nodeId?.takeIf(String::isNotBlank) }
        return nodeIds.toList()
    }

    private fun candidateBelongsToSelectedMethod(
        candidate: CandidateDraftChange,
        selectedMethodSignature: String?,
        baseGraph: GraphDocument,
    ): Boolean {
        val expectedSignature = selectedMethodSignature?.trim().orEmpty()
        if (expectedSignature.isEmpty()) {
            return true
        }
        if (!graphContainsMethodSignature(baseGraph, expectedSignature)) {
            return true
        }
        val candidateSignatures = resolveCandidateMethodSignatures(candidate, baseGraph)
        return candidateSignatures.isEmpty() || expectedSignature in candidateSignatures
    }

    private fun graphContainsMethodSignature(
        graph: GraphDocument,
        selectedMethodSignature: String,
    ): Boolean {
        return graph.nodes.any { node ->
            node.signature == selectedMethodSignature ||
                node.metadata["flow.anchorMethod"] == selectedMethodSignature ||
                node.metadata["flow.ownerMethod"] == selectedMethodSignature
        }
    }

    private fun resolveCandidateMethodSignatures(
        candidate: CandidateDraftChange,
        baseGraph: GraphDocument,
    ): Set<String> {
        val nodesById = baseGraph.nodes.associateBy(GraphNode::id)
        val signatures = linkedSetOf<String>()
        candidate.editScopes
            .mapNotNullTo(signatures) { scope -> scope.symbolSignature?.trim()?.takeIf(String::isNotEmpty) }
        candidate.targetNodeIds
            .mapNotNullTo(signatures) { nodeId -> resolveNodeMethodSignature(nodesById[nodeId]) }
        candidate.evidence
            .flatMap { finding -> finding.references }
            .mapNotNullTo(signatures) { reference -> resolveNodeMethodSignature(nodesById[reference.nodeId]) }
        candidate.graphPatch?.operations.orEmpty().forEach { operation ->
            resolveNodeMethodSignature(nodesById[operation.elementId])?.let(signatures::add)
            resolveNodeMethodSignature(nodesById[operation.node?.id])?.let(signatures::add)
            resolveNodeMethodSignature(operation.node)?.let(signatures::add)
            resolveNodeMethodSignature(nodesById[operation.edge?.fromNodeId])?.let(signatures::add)
            resolveNodeMethodSignature(nodesById[operation.edge?.toNodeId])?.let(signatures::add)
        }
        return signatures
    }

    private fun resolveNodeMethodSignature(node: GraphNode?): String? {
        if (node == null) {
            return null
        }
        return node.signature?.trim()?.takeIf(String::isNotEmpty)
            ?: node.metadata["flow.anchorMethod"]?.trim()?.takeIf(String::isNotEmpty)
            ?: node.metadata["flow.ownerMethod"]?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun updateAuditResultForConfirmation(
        auditResult: GraphPatchResult,
        changeId: String,
        confirmedEntry: DraftWorkbenchEntry?,
    ): GraphPatchResult {
        return auditResult.copy(
            candidateChanges = auditResult.candidateChanges.map { currentCandidate ->
                currentCandidate.confirmed(changeId, confirmedEntry)
            },
            newCandidateChanges = auditResult.newCandidateChanges.map { currentCandidate ->
                currentCandidate.confirmed(changeId, confirmedEntry)
            },
            auditSession = auditResult.auditSession?.copy(
                candidateChanges = auditResult.auditSession.candidateChanges.map { currentCandidate ->
                    currentCandidate.confirmed(changeId, confirmedEntry)
                },
            ),
        )
    }

    private fun updateAuditResultForUnconfirmation(
        auditResult: GraphPatchResult,
        changeId: String,
    ): GraphPatchResult {
        return auditResult.copy(
            candidateChanges = auditResult.candidateChanges.map { currentCandidate ->
                currentCandidate.unconfirmed(changeId)
            },
            newCandidateChanges = auditResult.newCandidateChanges.map { currentCandidate ->
                currentCandidate.unconfirmed(changeId)
            },
            auditSession = auditResult.auditSession?.copy(
                candidateChanges = auditResult.auditSession.candidateChanges.map { currentCandidate ->
                    currentCandidate.unconfirmed(changeId)
                },
            ),
        )
    }

    private fun CandidateDraftChange.confirmed(
        changeId: String,
        confirmedEntry: DraftWorkbenchEntry?,
    ): CandidateDraftChange {
        if (this.changeId != changeId) {
            return this
        }
        return copy(
            status = CandidateDraftChangeStatus.CONFIRMED,
            targetNodeIds = confirmedEntry?.targetNodeIds ?: targetNodeIds,
            graphPatch = confirmedEntry?.graphPatch ?: graphPatch,
        )
    }

    private fun CandidateDraftChange.unconfirmed(changeId: String): CandidateDraftChange {
        if (this.changeId != changeId) {
            return this
        }
        return copy(status = CandidateDraftChangeStatus.PENDING_CONFIRMATION)
    }
}
