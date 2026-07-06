package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
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

/** 从元数据读取 UI 坐标（ui.x / ui.y）；任一缺失返回 null。 */
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
 * GraphEditorPageRenderer 的纯展示 / 序列化辅助函数（P2-1 拆分，P2-6 DTO 化）。
 *
 * 这些函数无状态、把领域对象转换为前端可消费的 DTO（数据类），
 * 与 GraphEditorPageRenderer 的 HTML 渲染 / 启动载荷装配主流程解耦后便于复用与单独测试。
 *
 * Gson 反射序列化保证字段顺序与原 linkedMapOf 一致（serializeNulls 已开）。
 * 字段名拼写错误会在编译期暴露，重构也 IDE 友好。
 */

/** 解析差异条目的可读标题。 */
internal fun resolveDiffTitle(
    entry: GraphDiffEntry,
    document: GraphDocument,
): String = when (entry.elementKind) {
    GraphDiffElementKind.NODE -> document.nodes.firstOrNull { it.id == entry.elementId }?.title ?: entry.elementId
    GraphDiffElementKind.EDGE -> entry.elementId
}

// ---------- DTO 定义 ----------

internal data class GraphEdgeDto(
    val id: String,
    val type: String,
    val source: String,
    val target: String,
    val label: String?,
    val metadata: Map<String, String>?,
    val sourceTag: String,
)

internal data class GraphNodePositionDto(
    val x: Double,
    val y: Double,
)

internal data class GraphNodeDto(
    val id: String,
    val type: String,
    val title: String,
    val location: String?,
    val signature: String?,
    val inputs: List<String>,
    val outputs: List<String>,
    val doc: String?,
    val certainty: String,
    val bindingStatus: String,
    val diffStatus: String?,
    val sourceTag: String,
    val metadata: Map<String, String>?,
    val position: GraphNodePositionDto?,
)

internal data class GraphPatchOperationDto(
    val id: String,
    val action: String,
    val elementKind: String,
    val elementId: String,
    val title: String?,
    val summary: String?,
    val node: GraphNodeDto?,
    val edge: GraphEdgeDto?,
    val metadata: Map<String, String>?,
)

internal data class GraphPatchDto(
    val summary: String?,
    val operations: List<GraphPatchOperationDto>,
    val addedNodeIds: List<String>,
    val removedNodeIds: List<String>,
    val addedEdgeIds: List<String>,
    val removedEdgeIds: List<String>,
)

internal data class ReplayableQaRequestDto(
    val requestId: String,
    val kind: String,
    val question: String,
    val mode: String,
    val selectedNodeIds: List<String>,
    val sourceThreadId: String?,
    val baseSessionId: String?,
)

internal data class QaRequestRecoveryStateDto(
    val lastSubmittedRequest: ReplayableQaRequestDto?,
    val lastFailedRequest: ReplayableQaRequestDto?,
)

internal data class StageEligibilityDecisionDto(
    val target: String,
    val stageLabel: String,
    val allowed: Boolean,
    val message: String,
    val detailMessage: String?,
    val blockingThreadIds: List<String>,
    val unresolvedThreadIds: List<String>,
)

internal data class SourceNavigationStateDto(
    val nodeId: String?,
    val phase: String,
    val result: String?,
    val targetPath: String?,
    val line: Int?,
    val column: Int?,
    val errorMessage: String?,
)

internal data class ResultEvidenceReferenceDto(
    val nodeId: String?,
    val filePath: String?,
    val startLine: Int?,
    val endLine: Int?,
)

internal data class ResultEvidenceFindingDto(
    val id: String,
    val claim: String,
    val evidenceLevel: String,
    val references: List<ResultEvidenceReferenceDto>,
)

internal data class GraphSceneLayoutStateDto(
    val positions: Map<String, GraphNodePositionDto>,
)

internal data class ChildInvocationExpansionStateDto(
    val activeExpansionId: String?,
    val activeExpansionPath: List<String>,
    val collapsedExpansionIds: List<String>,
    val activeSiblingByParentContext: Map<String, String>,
)

internal data class InvocationExpansionSceneStateDto(
    val activeExpansionId: String?,
    val activeExpansionPath: List<String>,
    val collapsedExpansionIds: List<String>,
    val activeSiblingByParentContext: Map<String, String>,
    val blockPositions: Map<String, GraphNodePositionDto>,
    val lastChildStateByExpansionId: Map<String, ChildInvocationExpansionStateDto>,
    val contextMode: String,
)

internal data class GraphSceneStateDto(
    val selectedNodeId: String?,
    val anchorNodeId: String?,
    val collapsedNodeIds: List<String>,
    val invocationExpansionState: InvocationExpansionSceneStateDto,
    val layoutRevision: Long,
    val layoutState: GraphSceneLayoutStateDto,
)

internal data class AssistantFailureResultDto(
    val resultId: String,
    val message: String,
    val detailMessage: String?,
    val phase: String,
    val requestId: Long?,
    val sourceMessageType: String?,
    val createdAtEpochMillis: Long?,
)

internal data class GenerationPlanItemDto(
    val id: String,
    val title: String,
    val description: String,
    val risk: String,
    val targetPath: String?,
)

internal data class GenerationPlanDto(
    val source: String,
    val summary: String,
    val warnings: List<String>,
    val promptPreviewArtifactId: String?,
    val items: List<GenerationPlanItemDto>,
)

// ---------- DTO 工厂 ----------

/** 把边转换为前端 DTO。 */
internal fun edgeToDto(edge: GraphEdge): GraphEdgeDto = GraphEdgeDto(
    id = edge.id,
    type = edge.type.name,
    source = edge.fromNodeId,
    target = edge.toNodeId,
    label = edge.label,
    metadata = edge.metadata,
    sourceTag = edge.sourceTag.name,
)

/** 把节点转换为前端 DTO（含 layoutState 提供的 UI 坐标）。 */
internal fun nodeToDto(
    node: GraphNode,
    layoutState: GraphLayoutState? = null,
): GraphNodeDto = GraphNodeDto(
    id = node.id,
    type = node.type.name,
    title = node.title,
    location = node.location,
    signature = node.signature,
    inputs = node.inputs,
    outputs = node.outputs,
    doc = node.doc,
    certainty = node.certainty.name,
    bindingStatus = node.bindingStatus.name,
    diffStatus = node.diff.status.takeUnless { it.name == "MATCHED" }?.name,
    sourceTag = node.sourceTag.name,
    metadata = node.metadata.semanticMetadata(),
    position = (layoutState?.positions?.get(node.id)?.let { it.x to it.y } ?: node.metadata.uiPosition())?.let { (x, y) ->
        GraphNodePositionDto(x = x, y = y)
    },
)

/** 把单条补丁操作转换为前端 DTO。 */
internal fun patchOperationToDto(operation: GraphPatchOperation): GraphPatchOperationDto = GraphPatchOperationDto(
    id = operation.id,
    action = operation.action.name,
    elementKind = operation.elementKind.name,
    elementId = operation.elementId,
    title = operation.title,
    summary = operation.summary,
    node = operation.node?.let(::nodeToDto),
    edge = operation.edge?.let(::edgeToDto),
    metadata = operation.metadata,
)

/** 把补丁整体转换为前端 DTO。 */
internal fun patchToDto(patch: GraphPatch): GraphPatchDto = GraphPatchDto(
    summary = patch.summary,
    operations = patch.operations.map(::patchOperationToDto),
    addedNodeIds = patch.addedNodeIds,
    removedNodeIds = patch.removedNodeIds,
    addedEdgeIds = patch.addedEdgeIds,
    removedEdgeIds = patch.removedEdgeIds,
)

/** 把可重放的 QA 请求转换为前端 DTO。 */
internal fun replayableQaRequestToDto(
    request: ReplayableQaRequest,
): ReplayableQaRequestDto = ReplayableQaRequestDto(
    requestId = request.requestId,
    kind = request.kind.name,
    question = request.question,
    mode = request.mode.name,
    selectedNodeIds = request.selectedNodeIds,
    sourceThreadId = request.sourceThreadId,
    baseSessionId = request.baseSession?.sessionId,
)

/** 把 QA 请求恢复状态转换为前端 DTO。 */
internal fun qaRequestRecoveryStateToDto(
    state: QaRequestRecoveryState,
): QaRequestRecoveryStateDto = QaRequestRecoveryStateDto(
    lastSubmittedRequest = state.lastSubmittedRequest?.let(::replayableQaRequestToDto),
    lastFailedRequest = state.lastFailedRequest?.let(::replayableQaRequestToDto),
)

/** 把阶段准入决策转换为前端 DTO。 */
internal fun stageEligibilityDecisionToDto(
    decision: StageEligibilityDecision,
): StageEligibilityDecisionDto = StageEligibilityDecisionDto(
    target = decision.target.name,
    stageLabel = decision.stageLabel,
    allowed = decision.allowed,
    message = decision.message,
    detailMessage = decision.detailMessage,
    blockingThreadIds = decision.blockingThreadIds,
    unresolvedThreadIds = decision.unresolvedThreadIds,
)

/** 把源码跳转状态转换为前端 DTO。 */
internal fun sourceNavigationStateToDto(
    state: SourceNavigationState,
): SourceNavigationStateDto = SourceNavigationStateDto(
    nodeId = state.nodeId,
    phase = state.phase.name,
    result = state.result?.name,
    targetPath = state.targetPath,
    line = state.line,
    column = state.column,
    errorMessage = state.errorMessage,
)

/** 把单条证据结论转换为前端 DTO。 */
internal fun resultEvidenceFindingToDto(finding: ResultEvidenceFinding): ResultEvidenceFindingDto = ResultEvidenceFindingDto(
    id = finding.id,
    claim = finding.claim,
    evidenceLevel = finding.evidenceLevel.name,
    references = finding.references.map { reference ->
        ResultEvidenceReferenceDto(
            nodeId = reference.nodeId,
            filePath = reference.filePath,
            startLine = reference.startLine,
            endLine = reference.endLine,
        )
    },
)

/** 把单个场景的运行时状态转换为前端 DTO。 */
internal fun graphSceneStateToDto(
    state: GraphSceneState,
): GraphSceneStateDto = GraphSceneStateDto(
    selectedNodeId = state.selectedNodeId,
    anchorNodeId = state.anchorNodeId,
    collapsedNodeIds = state.collapsedNodeIds.toList(),
    invocationExpansionState = invocationExpansionSceneStateToDto(state.invocationExpansionState),
    layoutRevision = state.layoutRevision,
    layoutState = GraphSceneLayoutStateDto(
        positions = state.layoutState.positions.mapValues { (_, position) ->
            GraphNodePositionDto(x = position.x, y = position.y)
        },
    ),
)

private fun invocationExpansionSceneStateToDto(
    state: InvocationExpansionSceneState,
): InvocationExpansionSceneStateDto = InvocationExpansionSceneStateDto(
    activeExpansionId = state.activeExpansionId,
    activeExpansionPath = state.activeExpansionPath,
    collapsedExpansionIds = state.collapsedExpansionIds.toList(),
    activeSiblingByParentContext = state.activeSiblingByParentContext,
    blockPositions = state.blockPositions.mapValues { (_, position) ->
        GraphNodePositionDto(x = position.x, y = position.y)
    },
    lastChildStateByExpansionId = state.lastChildStateByExpansionId.mapValues { (_, childState) ->
        ChildInvocationExpansionStateDto(
            activeExpansionId = childState.activeExpansionId,
            activeExpansionPath = childState.activeExpansionPath,
            collapsedExpansionIds = childState.collapsedExpansionIds.toList(),
            activeSiblingByParentContext = childState.activeSiblingByParentContext,
        )
    },
    contextMode = state.contextMode.name,
)

/** 把场景状态集合映射为 sceneId → sceneState 的 DTO 结构。 */
internal fun sceneStatesToDto(
    sceneStates: Map<GraphSceneId, GraphSceneState>,
): Map<String, GraphSceneStateDto> =
    sceneStates.entries.associate { (sceneId, state) ->
        sceneId.name to graphSceneStateToDto(state)
    }

/** 把助手调用失败结果转换为前端 DTO。 */
internal fun assistantFailureResultToDto(
    failure: AssistantFailureResult,
): AssistantFailureResultDto = AssistantFailureResultDto(
    resultId = failure.resultId,
    message = failure.message,
    detailMessage = failure.detailMessage,
    phase = failure.phase,
    requestId = failure.requestId,
    sourceMessageType = failure.sourceMessageType,
    createdAtEpochMillis = failure.createdAtEpochMillis,
)

/** 把生成计划转换为前端 DTO。 */
internal fun generationPlanToDto(
    plan: GenerationPlan,
    promptPreviewArtifactId: String?,
): GenerationPlanDto = GenerationPlanDto(
    source = plan.source.name,
    summary = plan.summary,
    warnings = plan.warnings,
    promptPreviewArtifactId = promptPreviewArtifactId,
    items = plan.items.map { item ->
        GenerationPlanItemDto(
            id = item.id,
            title = item.title,
            description = item.description,
            risk = item.risk.name,
            targetPath = item.targetPath,
        )
    },
)
