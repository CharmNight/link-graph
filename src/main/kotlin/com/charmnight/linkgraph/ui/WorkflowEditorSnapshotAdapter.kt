package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.DraftPatchUndo
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot

/**
 * 将 UI 层持有的图编辑器状态快照转换为应用层使用的工作流编辑器快照。
 * 该方法把可视化界面收集到的所有状态字段（包括语义事实图、各类视图、草稿状态、QA 与差异结果等）
 * 统一搬运到应用层模型中，供工作流编辑场景消费。
 */
internal fun GraphEditorStateSnapshot.toWorkflowEditorSnapshot(): WorkflowEditorSnapshot {
    return WorkflowEditorSnapshot(
        semanticFactGraph = semanticFactGraph,
        workspaceBaseGraph = workspaceBaseGraph,
        workspaceGraph = workspaceGraph,
        designBaselineGraph = designBaselineGraph,
        trustedNavigationNodes = trustedNavigationNodes,
        factGraphView = factGraphView.toApplicationGraphView(),
        flowchartView = flowchartView.toApplicationGraphView(),
        resourceRelationView = resourceRelationView.toApplicationGraphView(),
        architectureGraphView = architectureGraphView.toApplicationGraphView(),
        classDiagramView = classDiagramView.toApplicationGraphView(),
        reviewGraphView = reviewGraphView.toApplicationGraphView(),
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
        workingGraphDirty = workingGraphDirty,
        lastGraphSource = lastGraphSource,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
        syncPreviewItems = syncPreviewItems,
        mermaidIssues = mermaidIssues,
        diff = diff,
        diffGraph = diffGraph,
        qaResult = qaResult,
        diffReviewResult = diffReviewResult,
        qaRequestState = qaRequestState,
        qaRequestRecoveryState = qaRequestRecoveryState,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = draftPatchPreview,
        draftPatchUndo = draftPatchUndoState?.let { undoState ->
            DraftPatchUndo(
                graphBeforeApply = undoState.graphBeforeApply,
                patchPreview = undoState.patchPreview,
            )
        },
        generationPlan = generationPlan,
        generationPlanDiscussionSession = generationPlanDiscussionSession,
        generatedCodeDrafts = generatedCodeDrafts,
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
    )
}

/**
 * 将语义层的事实图视图文档转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将语义层的流程图视图文档转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将语义层的资源关系视图文档转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将架构图计算结果转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.architecture.ArchitectureGraphResult.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将类图计算结果转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.architecture.ClassDiagramResult.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将评审图计算结果转换为应用层通用的图视图模型，
 * 保留可见图、完整图以及投影索引信息。
 */
private fun com.charmnight.linkgraph.review.ReviewGraphResult.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

/**
 * 将 UI 层的图编辑器状态快照转换为应用层快照模型，
 * 仅搬运应用层关心的核心字段，例如工作区图基线、当前工作区图、选中方法签名、
 * 草稿工作台状态、草稿补丁预览与撤销信息，以及 QA 与差异评审结果。
 */
internal fun GraphEditorStateSnapshot.toApplicationSnapshot(): ApplicationSnapshot {
    return ApplicationSnapshot(
        workspaceBaseGraph = workspaceBaseGraph,
        workspaceGraph = workspaceGraph,
        selectedMethodSignature = selectedMethodSignature,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = draftPatchPreview,
        draftPatchUndo = draftPatchUndoState?.let { undoState ->
            DraftPatchUndo(
                graphBeforeApply = undoState.graphBeforeApply,
                patchPreview = undoState.patchPreview,
            )
        },
        qaResult = qaResult,
        diffReviewResult = diffReviewResult,
    )
}
