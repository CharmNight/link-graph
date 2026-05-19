package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditScript
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode
import com.charmnight.linkgraph.application.workflow.FrontendGraphMutationSanitizer
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner

sealed interface WorkspaceGraphUseCaseResult {
    data class Loaded(val graph: GraphDocument, val source: String) : WorkspaceGraphUseCaseResult
    data class EditIgnored(val reason: String) : WorkspaceGraphUseCaseResult
    data class EditApplied(
        val expectedSnapshotRevision: Long,
        val graph: GraphDocument,
        val selectedMethodSignature: String?,
    ) : WorkspaceGraphUseCaseResult
    data class LayoutChanged(
        val positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ) : WorkspaceGraphUseCaseResult
    data class MermaidImported(
        val mermaid: String,
        val graph: GraphDocument,
        val issues: List<MermaidIssue>,
    ) : WorkspaceGraphUseCaseResult
    data class MermaidExported(val exported: String) : WorkspaceGraphUseCaseResult
    data object MissingDiffInputs : WorkspaceGraphUseCaseResult
    data class DiffShown(val result: GraphDifferResult) : WorkspaceGraphUseCaseResult
    data class SyncPreviewReady(val items: List<SyncPreviewItem>) : WorkspaceGraphUseCaseResult
}

class WorkspaceGraphUseCase internal constructor(
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    fun loadGraph(graph: GraphDocument, source: String): WorkspaceGraphUseCaseResult.Loaded =
        WorkspaceGraphUseCaseResult.Loaded(graph, source)

    fun applyFrontendEditScript(
        snapshot: WorkflowEditorSnapshot,
        script: GraphEditScript,
    ): WorkspaceGraphUseCaseResult {
        if (snapshot.workspaceRevision != script.baseWorkspaceRevision) {
            return WorkspaceGraphUseCaseResult.EditIgnored("workspace revision mismatch")
        }
        val projectionIndex = projectionIndexFor(snapshot, script)
        val permissionFailure = firstRejectedEditOperation(snapshot, script, projectionIndex)
        if (permissionFailure != null) {
            return WorkspaceGraphUseCaseResult.EditIgnored(permissionFailure)
        }
        return WorkspaceGraphUseCaseResult.EditApplied(
            expectedSnapshotRevision = snapshot.snapshotRevision,
            graph = applyEditScript(snapshot, script, projectionIndex),
            selectedMethodSignature = snapshot.selectedMethodSignature,
        )
    }

    fun importMermaid(mermaid: String): WorkspaceGraphUseCaseResult.MermaidImported {
        val parseResult = mermaidImporter.import(mermaid)
        val issues = mermaidValidator.validate(parseResult.document, parseResult.issues)
        return WorkspaceGraphUseCaseResult.MermaidImported(mermaid, parseResult.document, issues)
    }

    fun changeLayout(
        snapshot: WorkflowEditorSnapshot,
        positions: Map<String, com.charmnight.linkgraph.application.model.GraphLayoutPosition>,
    ): WorkspaceGraphUseCaseResult.LayoutChanged {
        if (snapshot.analysisDisplayMode == AnalysisDisplayMode.ARCHITECTURE_GRAPH ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.CLASS_DIAGRAM ||
            snapshot.analysisDisplayMode == AnalysisDisplayMode.REVIEW_GRAPH
        ) {
            return WorkspaceGraphUseCaseResult.LayoutChanged(emptyMap())
        }
        return WorkspaceGraphUseCaseResult.LayoutChanged(positions)
    }

    fun exportMermaid(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.MermaidExported {
        val document = snapshot.designBaselineGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
                snapshot.flowchartView.fullGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            } else {
                null
            }
            ?: currentVisibleGraph(snapshot)
        return WorkspaceGraphUseCaseResult.MermaidExported(mermaidExporter.export(document))
    }

    fun showDiffMode(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult {
        val codeGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() }
            ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        val designGraph = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.MissingDiffInputs
        return WorkspaceGraphUseCaseResult.DiffShown(graphDiffer.diff(codeGraph, designGraph))
    }

    fun requestSyncPreview(snapshot: WorkflowEditorSnapshot): WorkspaceGraphUseCaseResult.SyncPreviewReady {
        val previewItems = when {
            snapshot.diffGraph != null && snapshot.diff != null -> syncPreviewPlanner.plan(snapshot.diffGraph, snapshot.diff)
            snapshot.semanticFactGraph.nodes.isNotEmpty() || snapshot.semanticFactGraph.edges.isNotEmpty() -> {
                val designBaseline = snapshot.designBaselineGraph ?: return WorkspaceGraphUseCaseResult.SyncPreviewReady(emptyList())
                val result = graphDiffer.diff(snapshot.semanticFactGraph, designBaseline)
                syncPreviewPlanner.plan(result.graph, result.diff)
            }
            else -> emptyList()
        }
        return WorkspaceGraphUseCaseResult.SyncPreviewReady(previewItems)
    }

    private fun applyEditScript(
        snapshot: WorkflowEditorSnapshot,
        script: GraphEditScript,
        projectionIndex: GraphProjectionIndex,
    ): GraphDocument {
        val workingGraph = snapshot.workspaceGraph
        val trustedNodes = snapshot.trustedNavigationNodes
        val nodesById = LinkedHashMap(workingGraph.nodes.associateBy(GraphNode::id))
        val edgesById = LinkedHashMap(workingGraph.edges.associateBy(GraphEdge::id))

        script.operations.forEach { operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    val targetNodeId = resolveEditableNodeId(operation.node.id, projectionIndex, nodesById)
                    val sanitizedNode = frontendGraphMutationSanitizer.sanitize(
                        snapshot,
                        GraphDocument(nodes = listOf(operation.node)),
                    ).nodes.firstOrNull() ?: operation.node
                    val existingTrustedNode = trustedNodes[targetNodeId]
                    nodesById[targetNodeId] = existingTrustedNode?.copy(
                        title = sanitizedNode.title,
                        inputs = sanitizedNode.inputs,
                        outputs = sanitizedNode.outputs,
                        doc = sanitizedNode.doc,
                        metadata = existingTrustedNode.metadata + sanitizedNode.metadata,
                    ) ?: sanitizedNode.copy(id = targetNodeId)
                }
                is GraphEditOperation.RemoveNode -> {
                    val nodeIds = resolveRemovableNodeIds(operation.nodeId, projectionIndex)
                    nodeIds.forEach(nodesById::remove)
                    edgesById.entries.removeIf { (_, edge) -> edge.fromNodeId in nodeIds || edge.toNodeId in nodeIds }
                }
                is GraphEditOperation.UpsertEdge -> {
                    val targetEdgeId = resolveEditableEdgeId(operation.edge.id, projectionIndex, edgesById)
                    edgesById[targetEdgeId] = operation.edge.copy(id = targetEdgeId)
                }
                is GraphEditOperation.RemoveEdge -> {
                    resolveRemovableEdgeIds(operation.edgeId, projectionIndex).forEach(edgesById::remove)
                }
            }
        }
        return GraphDocument(
            nodes = nodesById.values.sortedBy { node -> node.id },
            edges = edgesById.values.sortedBy { edge -> edge.id },
            patch = workingGraph.patch,
        )
    }

    private fun projectionIndexFor(
        snapshot: WorkflowEditorSnapshot,
        script: GraphEditScript,
    ): GraphProjectionIndex =
        when (script.sceneId.toAnalysisDisplayMode()) {
            AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.projectionIndex
            AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.projectionIndex
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.projectionIndex
            AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.projectionIndex
            AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.projectionIndex
            AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.projectionIndex
            null -> snapshot.factGraphView.projectionIndex
        }

    private fun firstRejectedEditOperation(
        snapshot: WorkflowEditorSnapshot,
        script: GraphEditScript,
        projectionIndex: GraphProjectionIndex,
    ): String? {
        val nodesById = snapshot.workspaceGraph.nodes.associateBy(GraphNode::id)
        val edgesById = snapshot.workspaceGraph.edges.associateBy(GraphEdge::id)
        return script.operations.firstNotNullOfOrNull { operation ->
            when (operation) {
                is GraphEditOperation.UpsertNode -> {
                    val existing = nodesById.containsKey(operation.node.id) ||
                        projectionIndex.nodeMapping(operation.node.id)?.canonicalNodeIds.orEmpty().any(nodesById::containsKey)
                    val command = if (existing) GraphEditCommandKind.UPDATE_NODE else GraphEditCommandKind.ADD_NODE
                    if (canEditNode(operation.node.id, projectionIndex, command)) null else "node ${operation.node.id} rejects $command"
                }
                is GraphEditOperation.RemoveNode -> {
                    if (canEditNode(operation.nodeId, projectionIndex, GraphEditCommandKind.DELETE_NODE)) null else {
                        "node ${operation.nodeId} rejects ${GraphEditCommandKind.DELETE_NODE}"
                    }
                }
                is GraphEditOperation.UpsertEdge -> {
                    val targetEdgeId = resolveEditableEdgeId(operation.edge.id, projectionIndex, edgesById)
                    val existingEdge = edgesById[targetEdgeId]
                    val edgeEditable = if (existingEdge != null) {
                        operation.edge.copy(id = targetEdgeId) == existingEdge
                    } else {
                        canEditNode(operation.edge.fromNodeId, projectionIndex, GraphEditCommandKind.CONNECT_NODES) &&
                            canEditNode(operation.edge.toNodeId, projectionIndex, GraphEditCommandKind.CONNECT_NODES)
                    }
                    if (edgeEditable) null else "edge ${operation.edge.id} rejects upsert"
                }
                is GraphEditOperation.RemoveEdge -> {
                    if (canEditEdge(operation.edgeId, projectionIndex, GraphEditCommandKind.DELETE_EDGE)) null else {
                        "edge ${operation.edgeId} rejects ${GraphEditCommandKind.DELETE_EDGE}"
                    }
                }
            }
        }
    }

    private fun canEditNode(
        projectedNodeId: String,
        projectionIndex: GraphProjectionIndex,
        command: GraphEditCommandKind,
    ): Boolean {
        val mapping = projectionIndex.nodeMapping(projectedNodeId) ?: return command == GraphEditCommandKind.ADD_NODE
        return command in mapping.editableCommandKinds
    }

    private fun canEditEdge(
        projectedEdgeId: String,
        projectionIndex: GraphProjectionIndex,
        command: GraphEditCommandKind,
    ): Boolean {
        val mapping = projectionIndex.edgeMapping(projectedEdgeId) ?: return false
        return command in mapping.editableCommandKinds
    }

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
            projectedNodeId in nodesById -> projectedNodeId
            else -> projectedNodeId
        }
    }

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
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            GraphProjectionMappingKind.OVERFLOW_READONLY,
            -> emptySet()
        }
    }

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
            projectedEdgeId in edgesById -> projectedEdgeId
            else -> projectedEdgeId
        }
    }

    private fun resolveRemovableEdgeIds(
        projectedEdgeId: String,
        projectionIndex: GraphProjectionIndex,
    ): Set<String> {
        val mapping = projectionIndex.edgeMapping(projectedEdgeId) ?: return setOf(projectedEdgeId)
        return when (mapping.mappingKind) {
            GraphProjectionMappingKind.EXACT -> mapping.canonicalEdgeIds.toSet()
            GraphProjectionMappingKind.MERGED_ALIAS,
            GraphProjectionMappingKind.PATH_ALIAS,
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            GraphProjectionMappingKind.OVERFLOW_READONLY,
            -> emptySet()
        }
    }
}
