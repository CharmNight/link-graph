package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditOperation
import com.charmnight.linkgraph.ui.GraphEditScript
import com.charmnight.linkgraph.ui.GraphLayoutPosition
import com.charmnight.linkgraph.ui.toAnalysisDisplayMode
import com.charmnight.linkgraph.ui.view.GraphProjectionMappingKind

internal class GraphWorkspaceWorkflow(
    private val session: ProjectEditorSession,
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val copyToClipboard: (String) -> Boolean,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        session.mutate {
            loadGraph(graph, source)
        }
    }

    fun handleFrontendEditScript(script: GraphEditScript) {
        val snapshot = session.snapshot()
        if (snapshot.workspaceRevision != script.baseWorkspaceRevision) {
            return
        }
        val nextGraph = applyEditScript(snapshot, script)
        session.markGraphChanged(
            graph = nextGraph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            expectedRevision = snapshot.snapshotRevision,
            syncBrowser = false,
        )
    }

    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        session.mutate(syncBrowser = false) {
            markLayoutChanged(positions)
        }
    }

    fun importMermaid(mermaid: String): GraphDocument {
        val parseResult = mermaidImporter.import(mermaid)
        val issues = mermaidValidator.validate(parseResult.document, parseResult.issues)
        session.mutate {
            importMermaid(mermaid, parseResult.document, issues)
        }
        return parseResult.document
    }

    fun exportMermaid(): String {
        val snapshot = session.snapshot()
        val document = snapshot.designBaselineGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
                snapshot.flowchartView.fullGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            } else {
                null
            }
            ?: currentVisibleGraph(snapshot)
        return mermaidExporter.export(document).also { exported ->
            session.mutateBatch {
                markMermaidExported(exported)
                val copiedToClipboard = copyToClipboard(exported)
                workbench.markOperationFeedback(
                    com.charmnight.linkgraph.ui.OperationFeedbackLevel.SUCCESS,
                    if (copiedToClipboard) {
                        "已导出 Mermaid，并复制到剪贴板。"
                    } else {
                        "已导出 Mermaid。"
                    },
                )
            }
        }
    }

    fun showDiffMode(): GraphDifferResult? {
        val snapshot = session.snapshot()
        val codeGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() } ?: return null
        val designGraph = snapshot.designBaselineGraph ?: return null
        return graphDiffer.diff(codeGraph, designGraph).also { result ->
            session.mutate {
                showDiffMode(result.graph, result.diff)
            }
        }
    }

    fun requestSyncPreview(): List<SyncPreviewItem> {
        val snapshot = session.snapshot()
        val previewItems = when {
            snapshot.diffGraph != null && snapshot.diff != null -> syncPreviewPlanner.plan(snapshot.diffGraph, snapshot.diff)
            snapshot.semanticFactGraph.nodes.isNotEmpty() || snapshot.semanticFactGraph.edges.isNotEmpty() -> {
                val designBaseline = snapshot.designBaselineGraph ?: return emptyList()
                val result = graphDiffer.diff(snapshot.semanticFactGraph, designBaseline)
                syncPreviewPlanner.plan(result.graph, result.diff)
            }

            else -> emptyList()
        }
        session.mutate {
            workbench.requestSyncPreview(previewItems)
        }
        return previewItems
    }

    private fun applyEditScript(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        script: GraphEditScript,
    ): GraphDocument {
        val projectionIndex = when (script.sceneId.toAnalysisDisplayMode()) {
            AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.projectionIndex
            AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.projectionIndex
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.projectionIndex
            null -> snapshot.factGraphView.projectionIndex
        }
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

    private fun resolveEditableNodeId(
        projectedNodeId: String,
        projectionIndex: com.charmnight.linkgraph.ui.view.GraphProjectionIndex,
        nodesById: Map<String, GraphNode>,
    ): String {
        val mapping = projectionIndex.nodeMapping(projectedNodeId)
        return when {
            mapping == null -> projectedNodeId
            mapping.mappingKind == com.charmnight.linkgraph.ui.view.GraphProjectionMappingKind.EXACT &&
                mapping.canonicalNodeIds.size == 1 -> mapping.canonicalNodeIds.first()
            projectedNodeId in nodesById -> projectedNodeId
            else -> projectedNodeId
        }
    }

    private fun resolveRemovableNodeIds(
        projectedNodeId: String,
        projectionIndex: com.charmnight.linkgraph.ui.view.GraphProjectionIndex,
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
        projectionIndex: com.charmnight.linkgraph.ui.view.GraphProjectionIndex,
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
        projectionIndex: com.charmnight.linkgraph.ui.view.GraphProjectionIndex,
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
