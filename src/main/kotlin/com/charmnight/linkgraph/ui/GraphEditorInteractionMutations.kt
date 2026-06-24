package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch

/**
 * 应用一次 Mermaid 导入后的状态变更。
 *
 * 用导入的新图替换设计基线（如果传入为 null 则保留原基线），重置差异与同步预览相关字段，
 * 同时将快照版本号自增并记录最近交互类型，便于后续视图刷新与调试。
 */
internal fun GraphEditorStateSnapshot.withImportedMermaid(
    mermaid: String,
    graph: GraphDocument?,
    mermaidIssues: List<MermaidIssue>,
): GraphEditorStateSnapshot {
    val nextDesignBaselineGraph = graph ?: designBaselineGraph
    return copy(
        designBaselineGraph = nextDesignBaselineGraph,
        importedMermaid = mermaid,
        mermaidIssues = mermaidIssues,
        diff = null,
        diffGraph = null,
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "importMermaid",
    )
}

/**
 * 切换到差异展示模式。
 *
 * 设置当前差异视图对应的图与差异对象，调整 DIFF 场景下选区/锚点/布局的继承关系，
 * 切换当前场景到 DIFF，并把草稿补丁预览更新为该差异图自带的补丁。
 */
internal fun GraphEditorStateSnapshot.withShownDiffMode(
    graph: GraphDocument,
    diff: GraphDiff,
): GraphEditorStateSnapshot {
    val currentWorkspaceScene = currentSceneId.toAnalysisDisplayMode()?.toWorkspaceSceneId() ?: previousWorkspaceSceneId
    val diffSceneState = sceneState(GraphSceneId.DIFF).copy(
        selectedNodeId = resolveSelectedNodeId(
            graph = graph,
            selectedNodeId = sceneState(GraphSceneId.DIFF).selectedNodeId,
            selectedMethodSignature = selectedMethodSignature,
        ),
        anchorNodeId = sceneState(GraphSceneId.DIFF).anchorNodeId
            ?.takeIf { anchorNodeId -> graph.nodes.any { it.id == anchorNodeId } }
            ?: graph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(graph),
        layoutRevision = sceneState(GraphSceneId.DIFF).layoutRevision + 1,
    )
    return copy(
        diff = diff,
        diffGraph = graph,
        currentSceneId = GraphSceneId.DIFF,
        previousWorkspaceSceneId = currentWorkspaceScene,
        sceneStates = sceneStates.withSceneState(GraphSceneId.DIFF, diffSceneState),
        draftPatchPreview = graph.patch,
        lastMessageType = "showDiffMode",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 选中一个方法签名。
 *
 * 在当前可见图中根据签名找到对应节点并切换选区；若未匹配到则保留原选区。
 * 同时刷新助手上下文，让问答能够拿到最新选中状态。
 */
internal fun GraphEditorStateSnapshot.withSelectedMethod(
    signature: String,
): GraphEditorStateSnapshot {
    val graph = currentVisibleGraphForMutation()
    val sceneState = currentSceneState()
    val nextSelectedNodeId = findNodeIdBySignature(graph, signature) ?: sceneState.selectedNodeId
    return copy(
        selectedMethodSignature = signature,
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(selectedNodeId = nextSelectedNodeId),
        ),
        lastMessageType = "selectedMethod",
        snapshotRevision = snapshotRevision + 1,
    ).withAssistantContextFromCurrentState()
}

/**
 * 选中一个节点。
 *
 * 在当前场景的状态中把选中节点 ID 设置为指定值，并刷新助手上下文以反映新的选中状态。
 */
internal fun GraphEditorStateSnapshot.withSelectedNode(
    nodeId: String,
): GraphEditorStateSnapshot {
    val sceneState = currentSceneState()
    return copy(
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(selectedNodeId = nodeId),
        ),
        lastMessageType = "nodeSelected",
        snapshotRevision = snapshotRevision + 1,
    ).withAssistantContextFromCurrentState()
}

/**
 * 应用一次布局位置变更。
 *
 * 把传入的新位置合并到当前场景的布局状态中（覆盖同 ID 已有位置），并将布局版本号自增，
 * 以便驱动视图层重新计算节点渲染坐标。
 */
internal fun GraphEditorStateSnapshot.withLayoutChanged(
    positions: Map<String, GraphLayoutPosition>,
): GraphEditorStateSnapshot {
    val sceneState = currentSceneState()
    return copy(
        sceneStates = sceneStates.withSceneState(
            currentSceneId,
            sceneState.copy(
                layoutState = sceneState.layoutState.copy(
                    positions = sceneState.layoutState.positions + positions,
                ),
                layoutRevision = sceneState.layoutRevision + 1,
            ),
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "layoutChanged",
    )
}

/**
 * 发起一次跳转到源码的请求。
 *
 * 把源码导航状态置为运行中，并记录触发跳转的节点 ID，等待后续真实导航结果返回。
 */
internal fun GraphEditorStateSnapshot.withRequestedSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.RUNNING,
        ),
        lastMessageType = "requestSourceNavigation",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 记录一次成功的源码跳转。
 *
 * 把源码导航状态置为成功，并记录实际打开的文件路径与光标位置，便于 UI 展示跳转反馈或撤销。
 */
internal fun GraphEditorStateSnapshot.withOpenedSourceNavigation(
    nodeId: String,
    targetPath: String,
    line: Int?,
    column: Int?,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.SUCCEEDED,
            result = SourceNavigationResult.OPENED,
            targetPath = targetPath,
            line = line,
            column = column,
        ),
        lastMessageType = "sourceNavigationSucceeded",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 记录源码跳转未找到目标。
 *
 * 把源码导航状态置为未找到，表示节点对应的源码位置无法解析或不存在，便于 UI 给出相应提示。
 */
internal fun GraphEditorStateSnapshot.withMissingSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.NOT_FOUND,
        ),
        lastMessageType = "sourceNavigationNotFound",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 记录一次失败的源码跳转。
 *
 * 把源码导航状态置为失败，并携带底层错误信息，便于 UI 展示原因或供后续排查。
 */
internal fun GraphEditorStateSnapshot.withFailedSourceNavigation(
    nodeId: String,
    errorMessage: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.FAILED,
            errorMessage = errorMessage,
        ),
        lastMessageType = "sourceNavigationFailed",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 更新草稿补丁预览。
 *
 * 用传入的补丁替换当前草稿预览，仅更新预览数据与交互类型标记，不触碰图本身。
 */
internal fun GraphEditorStateSnapshot.withDraftPatchPreview(
    patch: GraphPatch,
): GraphEditorStateSnapshot {
    return copy(
        draftPatchPreview = patch,
        lastMessageType = "draftPatchPreview",
        snapshotRevision = snapshotRevision + 1,
    )
}

/**
 * 根据当前场景获取对应的可见子图，供变更函数读取需要操作的图数据。
 *
 * 不同工作区场景映射到不同的可见子图视图；差异场景下则直接使用差异图（可能为空）。
 */
private fun GraphEditorStateSnapshot.currentVisibleGraphForMutation(): GraphDocument {
    return when (currentSceneId) {
        GraphSceneId.WORKSPACE_FACT -> factGraphView.visibleGraph
        GraphSceneId.WORKSPACE_FLOWCHART -> flowchartView.visibleGraph
        GraphSceneId.WORKSPACE_RESOURCE_RELATION -> resourceRelationView.visibleGraph
        GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> classDiagramView.visibleGraph
        GraphSceneId.WORKSPACE_REVIEW_GRAPH -> reviewGraphView.visibleGraph
        GraphSceneId.DIFF -> diffGraph ?: GraphDocument()
    }
}
