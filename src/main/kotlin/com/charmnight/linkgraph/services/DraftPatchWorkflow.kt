package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.ui.DraftPatchApplyResult
import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 管理草稿补丁预览、应用、恢复与撤销。
 */
internal class DraftPatchWorkflow(
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 图补丁应用服务。 */
    private val graphPatchApplyService: GraphPatchApplyService,
    /** 工作图变更回调。 */
    private val onMarkGraphChanged: (
        graph: GraphDocument,
        preserveDraftPatchUndo: Boolean,
        syncBrowser: Boolean,
    ) -> Unit,
) {
    /**
     * 在前端展示草稿补丁预览。
     */
    fun previewDraftPatch(patch: GraphPatch) {
        session.mutateBatch {
            apply {
                workbench.markDraftPatchPreview(patch)
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                    patch.summary ?: "已生成草稿 patch 预览。",
                )
            }
        }
    }

    /**
     * 把当前草稿补丁预览真正应用到工作图。
     */
    fun applyDraftPatchPreview(operationIds: Set<String>? = null): GraphDocument? {
        val snapshot = session.snapshot()
        val patch = snapshot.draftPatchPreview ?: return null
        val baseGraph = currentWorkingGraph(snapshot)
        val selectedOperations = patch.operations.filter { operationIds == null || it.id in operationIds }
        session.mutate(syncBrowser = false) {
            workbench.markDraftPatchApplyUndo(baseGraph, patch)
        }
        val applied = graphPatchApplyService.apply(baseGraph, patch, operationIds)
        onMarkGraphChanged(
            applied,
            true,
            false,
        )
        session.mutateBatch {
            apply {
                workbench.clearDraftPatchPreview()
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                    "已将草稿 patch 应用到当前工作图。",
                )
            }
            apply {
                workbench.markDraftPatchApplyResult(buildDraftPatchApplyResult(selectedOperations, applied))
            }
        }
        return applied
    }

    /**
     * 清空当前草稿补丁预览。
     */
    fun clearDraftPatchPreview() {
        val snapshot = session.snapshot()
        if (snapshot.draftPatchPreview == null) {
            session.mutate {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING,
                    "当前没有可清空的草稿预览。",
                )
            }
            return
        }
        session.mutateBatch {
            apply {
                workbench.clearDraftPatchPreview()
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.INFO,
                    "已清空当前草稿预览。",
                )
            }
        }
    }

    /**
     * 从指定来源恢复草稿补丁预览。
     */
    fun restoreDraftPatchPreview(source: LinkGraphProjectService.DraftPatchPreviewSource): GraphPatch? {
        val snapshot = session.snapshot()
        val patch = when (source) {
            LinkGraphProjectService.DraftPatchPreviewSource.AUDIT -> snapshot.auditResult?.patch
            LinkGraphProjectService.DraftPatchPreviewSource.DIFF_REVIEW -> snapshot.diffReviewResult?.patch
            LinkGraphProjectService.DraftPatchPreviewSource.LAST_APPLIED -> snapshot.draftPatchUndoState?.patchPreview
        }
        if (patch == null) {
            session.mutate {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING,
                    "当前没有可恢复的草稿预览。",
                )
            }
            return null
        }
        session.mutateBatch {
            apply {
                workbench.markDraftPatchPreview(patch)
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.INFO,
                    "已恢复草稿预览。",
                )
            }
        }
        return patch
    }

    /**
     * 撤销上一次草稿补丁应用，并恢复预览状态。
     */
    fun undoLastDraftPatchApply(): GraphDocument? {
        val snapshot = session.snapshot()
        val undoState = snapshot.draftPatchUndoState
        if (undoState == null) {
            session.mutate {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.WARNING,
                    "当前没有可撤销的草稿写回。",
                )
            }
            return null
        }
        onMarkGraphChanged(
            undoState.graphBeforeApply,
            false,
            false,
        )
        session.mutateBatch {
            apply {
                workbench.clearDraftPatchApplyUndo()
            }
            undoState.patchPreview?.let { patch ->
                apply {
                    workbench.markDraftPatchPreview(patch)
                }
            }
            apply {
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                    "已撤销上次草稿写回，并恢复应用前工作图。",
                )
            }
            apply {
                markLastMessageType("undoDraftPatchApply")
            }
        }
        return undoState.graphBeforeApply
    }

    /**
     * 构造草稿补丁应用结果摘要。
     */
    private fun buildDraftPatchApplyResult(
        operations: List<GraphPatchOperation>,
        graph: GraphDocument,
    ): DraftPatchApplyResult {
        val appliedNodeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_NODE,
                GraphPatchAction.UPDATE_NODE,
                GraphPatchAction.ADD_ANNOTATION,
                GraphPatchAction.MARK_UNCERTAIN -> operation.node?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                GraphPatchAction.DELETE_NODE -> operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.NODE }
                else -> null
            }
        }.distinct()
        val appliedEdgeIds = operations.mapNotNull { operation ->
            when (operation.action) {
                GraphPatchAction.ADD_EDGE,
                GraphPatchAction.UPDATE_EDGE,
                GraphPatchAction.DELETE_EDGE -> operation.edge?.id ?: operation.elementId.takeIf { operation.elementKind == GraphDiffElementKind.EDGE }
                else -> null
            }
        }.distinct()
        val focusNodeId = appliedNodeIds.firstOrNull { nodeId -> graph.nodes.any { node -> node.id == nodeId } }
        return DraftPatchApplyResult(
            summary = "已应用 ${operations.size} 条草稿图变更。",
            appliedOperationCount = operations.size,
            appliedNodeIds = appliedNodeIds,
            appliedEdgeIds = appliedEdgeIds,
            focusNodeId = focusNodeId,
            appliedTargets = operations.mapNotNull(::resolveDraftPatchApplyTarget).distinct(),
        )
    }

    /**
     * 为补丁操作生成可读目标名称。
     */
    private fun resolveDraftPatchApplyTarget(operation: GraphPatchOperation): String? {
        operation.node?.let { node ->
            return node.signature?.substringBefore('(') ?: node.title
        }
        operation.edge?.let { edge ->
            return edge.label ?: "${edge.fromNodeId} -> ${edge.toNodeId}"
        }
        return operation.title ?: operation.summary ?: operation.elementId
    }

}
