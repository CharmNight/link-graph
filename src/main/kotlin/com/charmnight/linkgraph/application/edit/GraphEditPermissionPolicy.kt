package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/**
 * 图谱编辑权限策略：在投影视图场景下判断每一次节点/边增删改操作是否被允许，
 * 同时记录解析后的真实目标 ID，供后续应用层使用。
 */
class GraphEditPermissionPolicy {
    /**
     * 评估一次图谱编辑请求中所有操作的合法性，并产出问题清单与解析结果。
     *
     * @param snapshot 当前工作流编辑器快照，提供各投影视图与基础图谱
     * @param request 待评估的编辑请求，包含一组有序操作
     * @return 权限决策结果，包含问题清单与目标解析
     */
    fun evaluate(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
    ): GraphEditPermissionDecision {
        val projectionIndex = projectionIndexFor(snapshot, request)
        val nodesById = snapshot.workspaceGraph.nodes.associateBy(GraphNode::id)
        val edgesById = snapshot.workspaceGraph.edges.associateBy(GraphEdge::id)
        val upsertNodeTargetIds = request.operations
            .filterIsInstance<GraphEditOperation.UpsertNode>()
            .associate { operation ->
                operation.node.id to resolveEditableNodeId(operation.node.id, projectionIndex, nodesById)
            }
        val addedNodeIds = upsertNodeTargetIds.values.filterTo(linkedSetOf()) { nodeId -> nodeId !in nodesById }
        val prospectiveNodeIds = nodesById.keys + upsertNodeTargetIds.values
        val resolutionBuilder = GraphEditResolution.Builder()
        val issues = mutableListOf<GraphEditIssue>()

        request.operations.forEachIndexed { index, operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    val targetNodeId = upsertNodeTargetIds[operation.node.id]
                        ?: resolveEditableNodeId(operation.node.id, projectionIndex, nodesById)
                    resolutionBuilder.nodeTargetId(operation.node.id, targetNodeId)
                    // 只看 canonical 后的目标 ID 是否存在：投影源 ID 命中但目标 ID 不命中时
                    // 等价于"新增一个新节点"，应判 ADD_NODE 而不是 UPDATE_NODE（旧实现的 || 会误判）。
                    val existing = targetNodeId in nodesById
                    val command = if (existing) GraphEditCommandKind.UPDATE_NODE else GraphEditCommandKind.ADD_NODE
                    if (!canEditNode(operation.node.id, projectionIndex, command)) {
                        issues += readonlyNodeIssue(index, operation.node.id, command)
                    }
                }
                is GraphEditOperation.RemoveNode -> {
                    val removableNodeIds = resolveRemovableNodeIds(operation.nodeId, projectionIndex)
                    resolutionBuilder.nodeRemovalIds(operation.nodeId, removableNodeIds)
                    val shouldEvaluatePermission = projectionIndex.nodeMapping(operation.nodeId) != null ||
                        operation.nodeId in nodesById
                    if (shouldEvaluatePermission &&
                        !canEditNode(operation.nodeId, projectionIndex, GraphEditCommandKind.DELETE_NODE)
                    ) {
                        issues += readonlyNodeIssue(index, operation.nodeId, GraphEditCommandKind.DELETE_NODE)
                    }
                }
                is GraphEditOperation.UpsertEdge -> {
                    val targetEdgeId = resolveEditableEdgeId(operation.edge.id, projectionIndex, edgesById)
                    val targetFromNodeId = resolveEditableNodeId(operation.edge.fromNodeId, projectionIndex, nodesById)
                    val targetToNodeId = resolveEditableNodeId(operation.edge.toNodeId, projectionIndex, nodesById)
                    resolutionBuilder.edgeTargetId(operation.edge.id, targetEdgeId)
                    resolutionBuilder.nodeTargetId(operation.edge.fromNodeId, targetFromNodeId)
                    resolutionBuilder.nodeTargetId(operation.edge.toNodeId, targetToNodeId)
                    val existingEdge = edgesById[targetEdgeId]
                    val allowed = if (existingEdge != null) {
                        val requestedEdge = operation.edge.copy(
                            id = targetEdgeId,
                            fromNodeId = targetFromNodeId,
                            toNodeId = targetToNodeId,
                        )
                        requestedEdge == existingEdge ||
                            projectionIndex.edgeMapping(operation.edge.id)?.let { mapping ->
                                GraphEditCommandKind.INSERT_NODE_INTO_EDGE in mapping.editableCommandKinds
                            } == true
                    } else {
                        val endpointMissing = targetFromNodeId !in prospectiveNodeIds || targetToNodeId !in prospectiveNodeIds
                        endpointMissing ||
                            canConnectEndpoint(operation.edge.fromNodeId, targetFromNodeId, addedNodeIds, projectionIndex) &&
                            canConnectEndpoint(operation.edge.toNodeId, targetToNodeId, addedNodeIds, projectionIndex)
                    }
                    if (!allowed) {
                        issues += GraphEditIssue(
                            code = GraphEditIssueCode.READONLY_PROJECTION_EDGE,
                            message = "当前投影视图不允许编辑边 ${operation.edge.id}。",
                            operationIndex = index,
                            targetId = operation.edge.id,
                            retryable = false,
                        )
                    }
                }
                is GraphEditOperation.RemoveEdge -> {
                    val removableEdgeIds = resolveRemovableEdgeIds(operation.edgeId, projectionIndex)
                    resolutionBuilder.edgeRemovalIds(operation.edgeId, removableEdgeIds)
                    val shouldEvaluatePermission = projectionIndex.edgeMapping(operation.edgeId) != null ||
                        operation.edgeId in edgesById
                    if (shouldEvaluatePermission &&
                        !canEditEdge(operation.edgeId, projectionIndex, GraphEditCommandKind.DELETE_EDGE)
                    ) {
                        issues += GraphEditIssue(
                            code = GraphEditIssueCode.READONLY_PROJECTION_EDGE,
                            message = "当前投影视图不允许删除边 ${operation.edgeId}。",
                            operationIndex = index,
                            targetId = operation.edgeId,
                            retryable = false,
                        )
                    }
                }
            }
        }

        return GraphEditPermissionDecision(issues = issues, resolution = resolutionBuilder.build())
    }

    /** 判断连接端点是否合法：要么属于本次新增节点，要么该端点在当前视图允许被连接。 */
    private fun canConnectEndpoint(
        projectedNodeId: String,
        targetNodeId: String,
        addedNodeIds: Set<String>,
        projectionIndex: GraphProjectionIndex,
    ): Boolean =
        targetNodeId in addedNodeIds ||
            canEditNode(projectedNodeId, projectionIndex, GraphEditCommandKind.CONNECT_NODES)

    /** 根据请求中的场景 ID 选择对应的投影视图索引，缺省时回落到事实图谱视图。 */
    private fun projectionIndexFor(
        snapshot: WorkflowEditorSnapshot,
        request: GraphEditRequest,
    ): GraphProjectionIndex =
        when (request.sceneId.toAnalysisDisplayMode()) {
            AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.projectionIndex
            AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.projectionIndex
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.projectionIndex
            AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.projectionIndex
            AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.projectionIndex
            AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.projectionIndex
            null -> snapshot.factGraphView.projectionIndex
        }

    /** 判断在当前投影视图下能否对节点执行指定命令；无投影映射时仅允许新增节点。 */
    private fun canEditNode(
        projectedNodeId: String,
        projectionIndex: GraphProjectionIndex,
        command: GraphEditCommandKind,
    ): Boolean {
        val mapping = projectionIndex.nodeMapping(projectedNodeId) ?: return command == GraphEditCommandKind.ADD_NODE
        return command in mapping.editableCommandKinds
    }

    /** 判断在当前投影视图下能否对边执行指定命令；无投影映射则一律不允许。 */
    private fun canEditEdge(
        projectedEdgeId: String,
        projectionIndex: GraphProjectionIndex,
        command: GraphEditCommandKind,
    ): Boolean {
        val mapping = projectionIndex.edgeMapping(projectedEdgeId) ?: return false
        return command in mapping.editableCommandKinds
    }

    /** 把投影节点 ID 解析为真实可编辑的节点 ID：精确映射取唯一规范 ID，否则按原值处理。 */
    private fun resolveEditableNodeId(
        projectedNodeId: String,
        projectionIndex: GraphProjectionIndex,
        nodesById: Map<String, GraphNode>,
    ): String {
        val mapping = projectionIndex.nodeMapping(projectedNodeId)
        return when {
            mapping == null -> projectedNodeId
            mapping.mappingKind == GraphProjectionMappingKind.EXACT &&
                mapping.canonicalNodeIds.size == 1 -> mapping.canonicalNodeIds.first()
            else -> projectedNodeId
        }
    }

    /** 计算投影节点实际可被删除的底层节点 ID 集合：精确/合并别名映射返回全部规范 ID，其它只读映射返回空。 */
    private fun resolveRemovableNodeIds(
        projectedNodeId: String,
        projectionIndex: GraphProjectionIndex,
    ): Set<String> {
        val mapping = projectionIndex.nodeMapping(projectedNodeId) ?: return setOf(projectedNodeId)
        return when (mapping.mappingKind) {
            GraphProjectionMappingKind.EXACT,
            GraphProjectionMappingKind.MERGED_ALIAS,
            -> mapping.canonicalNodeIds.toSet()
            GraphProjectionMappingKind.PATH_ALIAS,
            GraphProjectionMappingKind.INDEXED_READONLY,
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            GraphProjectionMappingKind.OVERFLOW_READONLY,
            -> emptySet()
        }
    }

    /** 把投影边 ID 解析为真实可编辑的边 ID：精确映射取唯一规范 ID，否则按原值处理。 */
    private fun resolveEditableEdgeId(
        projectedEdgeId: String,
        projectionIndex: GraphProjectionIndex,
        edgesById: Map<String, GraphEdge>,
    ): String {
        val mapping = projectionIndex.edgeMapping(projectedEdgeId)
        return when {
            mapping == null -> projectedEdgeId
            mapping.mappingKind == GraphProjectionMappingKind.EXACT &&
                mapping.canonicalEdgeIds.size == 1 -> mapping.canonicalEdgeIds.first()
            else -> projectedEdgeId
        }
    }

    /** 计算投影边实际可被删除的底层边 ID 集合：仅精确映射返回规范 ID，其它只读映射返回空。 */
    private fun resolveRemovableEdgeIds(
        projectedEdgeId: String,
        projectionIndex: GraphProjectionIndex,
    ): Set<String> {
        val mapping = projectionIndex.edgeMapping(projectedEdgeId) ?: return setOf(projectedEdgeId)
        return when (mapping.mappingKind) {
            GraphProjectionMappingKind.EXACT -> mapping.canonicalEdgeIds.toSet()
            GraphProjectionMappingKind.MERGED_ALIAS,
            GraphProjectionMappingKind.PATH_ALIAS,
            GraphProjectionMappingKind.INDEXED_READONLY,
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            GraphProjectionMappingKind.OVERFLOW_READONLY,
            -> emptySet()
        }
    }

    /** 构造一条"只读投影节点"问题，描述当前视图不允许对该节点执行该命令。 */
    private fun readonlyNodeIssue(
        index: Int,
        nodeId: String,
        command: GraphEditCommandKind,
    ): GraphEditIssue =
        GraphEditIssue(
            code = GraphEditIssueCode.READONLY_PROJECTION_NODE,
            message = "当前投影视图不允许对节点 $nodeId 执行 $command。",
            operationIndex = index,
            targetId = nodeId,
            retryable = false,
        )
}
