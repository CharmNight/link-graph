package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.workbench.AssistantFailureResult
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

/** UI 布局相关元数据前缀（与 GraphEditorPageRenderer.companion 内常量保持一致）。 */
private const val UI_PREFIX = "ui."
/** 布局元数据前缀。 */
private const val LAYOUT_PREFIX = "layout."

/** 从 metadata 读取 UI 坐标（ui.x / ui.y）；任一缺失返回 null。 */
internal fun Map<String, String>.uiPosition(): Pair<Double, Double>? {
    val x = this[GraphMetadataKeys.Ui.X]?.toDoubleOrNull() ?: return null
    val y = this[GraphMetadataKeys.Ui.Y]?.toDoubleOrNull() ?: return null
    return x to y
}

/** 过滤掉纯 UI 布局相关元数据，只保留语义元数据；全空时返回 null。 */
internal fun Map<String, String>?.semanticMetadata(): Map<String, String>? {
    if (this == null) {
        return null
    }
    val filtered = this.filterKeys { key ->
        !key.startsWith(UI_PREFIX) && !key.startsWith(LAYOUT_PREFIX)
    }
    return filtered.ifEmpty { null }
}

/**
 * GraphEditorPageRenderer 的纯展示 / 序列化 helper（P2-1 拆分）。
 *
 * 这些函数无状态、把领域对象转换为前端可消费的字符串 / Map，
 * 与 GraphEditorPageRenderer 的 HTML 渲染 / bootstrap payload 装配主流程解耦后便于复用与单独测试。
 */

/**
 * 解析差异条目的可读标题。
 *
 * NODE 类条目优先用文档中对应节点的 title；EDGE 类条目直接用 elementId（边没有 title 字段）。
 */
internal fun resolveDiffTitle(
    entry: GraphDiffEntry,
    document: GraphDocument,
): String = when (entry.elementKind) {
    GraphDiffElementKind.NODE -> document.nodes.firstOrNull { it.id == entry.elementId }?.title ?: entry.elementId
    GraphDiffElementKind.EDGE -> entry.elementId
}

/** 把边转换为前端使用的 Map 结构（id / type / source / target / label / metadata / sourceTag）。 */
internal fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
    "id" to edge.id,
    "type" to edge.type.name,
    "source" to edge.fromNodeId,
    "target" to edge.toNodeId,
    "label" to edge.label,
    "metadata" to edge.metadata,
    "sourceTag" to edge.sourceTag.name,
)

/** 把节点转换为前端使用的 Map 结构（含 layoutState 提供的 UI 坐标）。 */
internal fun nodeToMap(
    node: GraphNode,
    layoutState: GraphLayoutState? = null,
): Map<String, Any?> = linkedMapOf(
    "id" to node.id,
    "type" to node.type.name,
    "title" to node.title,
    "location" to node.location,
    "signature" to node.signature,
    "inputs" to node.inputs,
    "outputs" to node.outputs,
    "doc" to node.doc,
    "certainty" to node.certainty.name,
    "bindingStatus" to node.bindingStatus.name,
    "diffStatus" to node.diff.status.takeUnless { it.name == "MATCHED" }?.name,
    "sourceTag" to node.sourceTag.name,
    "metadata" to node.metadata.semanticMetadata(),
    "position" to (layoutState?.positions?.get(node.id)?.let { it.x to it.y } ?: node.metadata.uiPosition())?.let { position ->
        linkedMapOf(
            "x" to position.first,
            "y" to position.second,
        )
    },
)

/** 把补丁整体（summary + operations + added/removed ID 集合）转换为前端 Map。 */
internal fun patchToMap(patch: GraphPatch): Map<String, Any?> = linkedMapOf(
    "summary" to patch.summary,
    "operations" to patch.operations.map(::patchOperationToMap),
    "addedNodeIds" to patch.addedNodeIds,
    "removedNodeIds" to patch.removedNodeIds,
    "addedEdgeIds" to patch.addedEdgeIds,
    "removedEdgeIds" to patch.removedEdgeIds,
)

/** 把单条补丁操作（含 node/edge 引用）转换为前端 Map。 */
internal fun patchOperationToMap(operation: GraphPatchOperation): Map<String, Any?> = linkedMapOf(
    "id" to operation.id,
    "action" to operation.action.name,
    "elementKind" to operation.elementKind.name,
    "elementId" to operation.elementId,
    "title" to operation.title,
    "summary" to operation.summary,
    "node" to operation.node?.let(::nodeToMap),
    "edge" to operation.edge?.let(::edgeToMap),
    "metadata" to operation.metadata,
)

/** 把 QA 请求恢复状态（上次提交 / 上次失败）展开为前端字段。 */
internal fun qaRequestRecoveryStateToMap(
    state: QaRequestRecoveryState,
): Map<String, Any?> = linkedMapOf(
    "lastSubmittedRequest" to state.lastSubmittedRequest?.let(::replayableQaRequestToMap),
    "lastFailedRequest" to state.lastFailedRequest?.let(::replayableQaRequestToMap),
)

/** 把可重放的 QA 请求结构（用于失败后重试或回放）展开为前端字段。 */
internal fun replayableQaRequestToMap(
    request: ReplayableQaRequest,
): Map<String, Any?> = linkedMapOf(
    "requestId" to request.requestId,
    "kind" to request.kind.name,
    "question" to request.question,
    "mode" to request.mode.name,
    "selectedNodeIds" to request.selectedNodeIds,
    "sourceThreadId" to request.sourceThreadId,
    "baseSessionId" to request.baseSession?.sessionId,
)

/** 把阶段准入决策（target / allowed / blocking threads 等）转换为前端 Map。 */
internal fun stageEligibilityDecisionToMap(
    decision: StageEligibilityDecision,
): Map<String, Any?> = linkedMapOf(
    "target" to decision.target.name,
    "stageLabel" to decision.stageLabel,
    "allowed" to decision.allowed,
    "message" to decision.message,
    "detailMessage" to decision.detailMessage,
    "blockingThreadIds" to decision.blockingThreadIds,
    "unresolvedThreadIds" to decision.unresolvedThreadIds,
)

/** 把源码跳转状态转换成前端可消费的映射。 */
internal fun sourceNavigationStateToMap(
    state: SourceNavigationState,
): Map<String, Any?> = linkedMapOf(
    "nodeId" to state.nodeId,
    "phase" to state.phase.name,
    "result" to state.result?.name,
    "targetPath" to state.targetPath,
    "line" to state.line,
    "column" to state.column,
    "errorMessage" to state.errorMessage,
)

/** 把单条证据结论（id / claim / evidenceLevel / references）转换为前端 Map。 */
internal fun resultEvidenceFindingToMap(finding: ResultEvidenceFinding): Map<String, Any?> = linkedMapOf(
    "id" to finding.id,
    "claim" to finding.claim,
    "evidenceLevel" to finding.evidenceLevel.name,
    "references" to finding.references.map { reference ->
        linkedMapOf(
            "nodeId" to reference.nodeId,
            "filePath" to reference.filePath,
            "startLine" to reference.startLine,
            "endLine" to reference.endLine,
        )
    },
)

/** 把场景状态集合映射为 sceneId → sceneState 的前端结构。 */
internal fun sceneStatesToMap(
    sceneStates: Map<GraphSceneId, GraphSceneState>,
): Map<String, Any?> =
    sceneStates.entries.associate { (sceneId, state) ->
        sceneId.name to graphSceneStateToMap(state)
    }

/** 把单个场景的运行时状态（选中节点、锚点、折叠节点、布局）转换为前端结构。 */
internal fun graphSceneStateToMap(
    state: GraphSceneState,
): Map<String, Any?> = linkedMapOf(
    "selectedNodeId" to state.selectedNodeId,
    "anchorNodeId" to state.anchorNodeId,
    "collapsedNodeIds" to state.collapsedNodeIds.toList(),
    "layoutRevision" to state.layoutRevision,
    "layoutState" to linkedMapOf(
        "positions" to state.layoutState.positions.mapValues { (_, position) ->
            linkedMapOf(
                "x" to position.x,
                "y" to position.y,
            )
        },
    ),
)

/** 把助手调用失败结果转换为前端字段，携带错误消息、阶段与时间戳。 */
internal fun assistantFailureResultToMap(
    failure: AssistantFailureResult,
): Map<String, Any?> = linkedMapOf(
    "resultId" to failure.resultId,
    "message" to failure.message,
    "detailMessage" to failure.detailMessage,
    "phase" to failure.phase,
    "requestId" to failure.requestId,
    "sourceMessageType" to failure.sourceMessageType,
    "createdAtEpochMillis" to failure.createdAtEpochMillis,
)

/** 把生成计划（含若干变更项、风险等级和目标路径）转换为前端结构。 */
internal fun generationPlanToMap(
    plan: GenerationPlan,
    promptPreviewArtifactId: String?,
): Map<String, Any?> = linkedMapOf(
    "source" to plan.source.name,
    "summary" to plan.summary,
    "warnings" to plan.warnings,
    "promptPreviewArtifactId" to promptPreviewArtifactId,
    "items" to plan.items.map { item ->
        linkedMapOf(
            "id" to item.id,
            "title" to item.title,
            "description" to item.description,
            "risk" to item.risk.name,
            "targetPath" to item.targetPath,
        )
    },
)

