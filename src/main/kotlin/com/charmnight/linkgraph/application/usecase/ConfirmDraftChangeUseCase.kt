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

/**
 * 确认草稿变更用例的输出结果密封接口，覆盖缺失候选、被拒绝以及确认成功三类场景。
 */
sealed interface ConfirmDraftChangeUseCaseResult {
    /** 用户请求确认的候选变更在当前 QA 结果中找不到对应记录。 */
    data object MissingCandidate : ConfirmDraftChangeUseCaseResult

    /** 候选变更因不满足准入条件（缺少证据或方法归属不符）而被拒绝写入草稿层。 */
    data class Rejected(
        /** 拒绝原因，用于直接展示给用户。 */
        val reason: String,
    ) : ConfirmDraftChangeUseCaseResult

    /** 候选变更已成功落入草稿层，并附带重建后的图谱与最新 QA 状态。 */
    data class Confirmed(
        /** 被确认的候选变更。 */
        val candidate: CandidateDraftChange,
        /** 该候选变更波及到的全部节点 ID，用于驱动界面高亮。 */
        val observedNodeIds: List<String>,
        /** 写入草稿层后形成的草稿条目，可能因合并等情况为空。 */
        val confirmedEntry: DraftWorkbenchEntry?,
        /** 写入后最新的草稿工作台状态。 */
        val draftState: DraftWorkbenchState,
        /** 在基础图谱之上叠加全部草稿条目重建出来的图文档。 */
        val rebuiltGraph: GraphDocument,
        /** 同步标注了确认状态的 QA 结果。 */
        val updatedQaResult: GraphPatchResult,
    ) : ConfirmDraftChangeUseCaseResult
}

/**
 * 取消确认草稿变更用例的输出结果密封接口，覆盖草稿条目不存在以及取消成功两类场景。
 */
sealed interface UnconfirmDraftChangeUseCaseResult {
    /** 在草稿工作台中找不到与指定变更对应的草稿条目。 */
    data object MissingEntry : UnconfirmDraftChangeUseCaseResult

    /** 已从草稿层移除条目，并附带重建后的图谱与最新 QA 状态。 */
    data class Unconfirmed(
        /** 被移除的草稿条目。 */
        val removedEntry: DraftWorkbenchEntry,
        /** 移除后最新的草稿工作台状态。 */
        val draftState: DraftWorkbenchState,
        /** 重新叠加剩余草稿条目后得到的图文档。 */
        val rebuiltGraph: GraphDocument,
        /** 同步恢复了待确认状态的 QA 结果。 */
        val updatedQaResult: GraphPatchResult,
    ) : UnconfirmDraftChangeUseCaseResult
}

/**
 * 草稿变更确认/取消确认用例，负责将 LLM 提出的候选变更落地到草稿工作台，
 * 并对图谱与 QA 结果做相应的同步重建。
 */
class ConfirmDraftChangeUseCase(
    /** 草稿工作台服务，负责草稿条目的实际增删与状态维护。 */
    private val draftWorkbenchService: DraftWorkbenchService,
    /** 图谱补丁应用服务，用于把草稿补丁叠加上图谱得到预览结果。 */
    private val graphPatchApplyService: GraphPatchApplyService,
) {
    /**
     * 确认一条候选变更，将其写入草稿工作台并联动刷新图谱与 QA 结果。
     *
     * @param snapshot 当前应用快照，提供基础图谱与上下文
     * @param qaResult 当前 QA 结果，包含所有候选变更
     * @param changeId 待确认的候选变更 ID
     * @return 确认操作的结果
     */
    fun confirm(
        snapshot: ApplicationSnapshot,
        qaResult: GraphPatchResult,
        changeId: String,
    ): ConfirmDraftChangeUseCaseResult {
        val candidate = qaResult.candidateChanges.firstOrNull { it.changeId == changeId }
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
            updatedQaResult = updateQaResultForConfirmation(qaResult, changeId, confirmedEntry),
        )
    }

    /**
     * 取消一条已确认的草稿变更，将其从草稿工作台中移除并回滚相关 QA 状态。
     *
     * @param snapshot 当前应用快照
     * @param qaResult 当前 QA 结果
     * @param changeId 待取消确认的候选变更 ID
     * @return 取消确认操作的结果
     */
    fun unconfirm(
        snapshot: ApplicationSnapshot,
        qaResult: GraphPatchResult,
        changeId: String,
    ): UnconfirmDraftChangeUseCaseResult {
        val removal = draftWorkbenchService.unconfirmCandidateChange(snapshot.draftWorkbenchState, changeId)
        val removedEntry = removal.removedEntry ?: return UnconfirmDraftChangeUseCaseResult.MissingEntry
        return UnconfirmDraftChangeUseCaseResult.Unconfirmed(
            removedEntry = removedEntry,
            draftState = removal.draftState,
            rebuiltGraph = rebuildConfirmedDraftGraph(snapshot, removal.draftState),
            updatedQaResult = updateQaResultForUnconfirmation(qaResult, changeId),
        )
    }

    /**
     * 在工作台基础图谱之上依次叠加所有已确认的草稿条目，得到预览用的图文档。
     * 若某条目没有补丁则跳过，保持当前叠加结果。
     */
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

    /**
     * 汇总候选变更所涉及的全部节点 ID，集合来源包括目标节点、补丁操作中的节点/元素以及证据引用。
     */
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

    /**
     * 判断候选变更是否归属当前选中的方法：
     * 若用户没有选中方法，或基础图谱中根本不存在该方法签名，则视为不限制；
     * 否则要求候选变更关联的方法签名集合包含被选中签名。
     */
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

    /** 判断图谱中是否存在节点直接持有或通过锚点/属主元数据持有指定方法签名。 */
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

    /**
     * 汇总候选变更可能关联到的方法签名，来源涵盖编辑作用域、目标节点、证据引用
     * 以及补丁中出现的节点/边端点。
     */
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

    /** 从节点上解析出方法签名，依次回退到节点签名以及 flow.anchorMethod / flow.ownerMethod 元数据。 */
    private fun resolveNodeMethodSignature(node: GraphNode?): String? {
        if (node == null) {
            return null
        }
        return node.signature?.trim()?.takeIf(String::isNotEmpty)
            ?: node.metadata["flow.anchorMethod"]?.trim()?.takeIf(String::isNotEmpty)
            ?: node.metadata["flow.ownerMethod"]?.trim()?.takeIf(String::isNotEmpty)
    }

    /**
     * 在 QA 结果中将匹配的候选变更标记为已确认，并把草稿条目信息同步回候选，
     * 同时刷新主候选、新增候选以及会话级候选三处列表。
     */
    private fun updateQaResultForConfirmation(
        qaResult: GraphPatchResult,
        changeId: String,
        confirmedEntry: DraftWorkbenchEntry?,
    ): GraphPatchResult {
        return qaResult.copy(
            candidateChanges = qaResult.candidateChanges.map { currentCandidate ->
                currentCandidate.confirmed(changeId, confirmedEntry)
            },
            newCandidateChanges = qaResult.newCandidateChanges.map { currentCandidate ->
                currentCandidate.confirmed(changeId, confirmedEntry)
            },
            qaSession = qaResult.qaSession?.copy(
                candidateChanges = qaResult.qaSession.candidateChanges.map { currentCandidate ->
                    currentCandidate.confirmed(changeId, confirmedEntry)
                },
            ),
        )
    }

    /** 在 QA 结果中将匹配的候选变更恢复为待确认状态，覆盖主候选、新增候选以及会话级候选三处列表。 */
    private fun updateQaResultForUnconfirmation(
        qaResult: GraphPatchResult,
        changeId: String,
    ): GraphPatchResult {
        return qaResult.copy(
            candidateChanges = qaResult.candidateChanges.map { currentCandidate ->
                currentCandidate.unconfirmed(changeId)
            },
            newCandidateChanges = qaResult.newCandidateChanges.map { currentCandidate ->
                currentCandidate.unconfirmed(changeId)
            },
            qaSession = qaResult.qaSession?.copy(
                candidateChanges = qaResult.qaSession.candidateChanges.map { currentCandidate ->
                    currentCandidate.unconfirmed(changeId)
                },
            ),
        )
    }

    /** 当候选变更 ID 匹配时，将其标记为已确认，并回填来自草稿条目的目标节点与补丁。 */
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

    /** 当候选变更 ID 匹配时，将其状态恢复为待确认。 */
    private fun CandidateDraftChange.unconfirmed(changeId: String): CandidateDraftChange {
        if (this.changeId != changeId) {
            return this
        }
        return copy(status = CandidateDraftChangeStatus.PENDING_CONFIRMATION)
    }
}
