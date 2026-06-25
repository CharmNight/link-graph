package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.toSummary
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphSummary
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/**
 * 包视图投影器（P2-1 真正的架构分解）。
 *
 * 从 ArchitectureGraphProjector.projectPackageGraph() 抽出的独立 class。
 * 负责把架构索引转换为以包为单位的视图。
 */
internal class PackageGraphProjector(
    private val graphDocumentBuilder: (
        index: ArchitectureGraphIndex,
        nodes: List<ArchitectureNode>,
        includeClassEdges: Boolean,
        viewMode: AnalysisDisplayMode,
        request: IndexedGraphRequest,
        displayContexts: Map<String, ArchitectureGraphProjector.StructureDisplayContext>,
    ) -> GraphDocument,
    private val computeDisplayContexts: (List<ArchitectureNode>) -> Map<String, ArchitectureGraphProjector.StructureDisplayContext>,
    private val viewportPolicy: GraphViewportPolicy,
    private val enrichGraph: (GraphDocument) -> GraphDocument,
    private val buildPresentation: (GraphDocument, GraphDocument, String?) -> com.charmnight.linkgraph.presentation.GraphViewPresentation,
    private val buildProjectionIndex: (GraphDocument) -> GraphProjectionIndex,
    private val selectAnchorNodeId: (GraphDocument) -> String?,
) {
    fun project(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
        cacheState: String,
        freshness: IndexedGraphFreshness,
    ): ArchitectureGraphResult {
        val packageViewKinds = setOf(
            ArchitectureNodeKind.PACKAGE,
            ArchitectureNodeKind.RESOURCE,
            ArchitectureNodeKind.LIBRARY,
            ArchitectureNodeKind.JDK,
        )
        val candidateNodes = index.graph.nodes.filter { node ->
            node.kind in packageViewKinds && node.isVisibleProjectStructureNode(index)
        }
        val packageNodes = candidateNodes.filter { node -> node.kind == ArchitectureNodeKind.PACKAGE }
        val relationshipNodeIds = index.graph.edges
            .asSequence()
            .filter { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .toSet()
        val scopedPackage = (request.scope as? IndexedGraphScope.Package)?.qualifiedName?.takeIf(String::isNotBlank)
        val scopedNodeIds = scopedPackage
            ?.let { packageName ->
                packageNodes
                    .filter { node -> node.qualifiedName == packageName || node.qualifiedName.startsWith("$packageName.") }
                    .mapTo(linkedSetOf(), ArchitectureNode::id)
            }
            .orEmpty()
        val fullGraphNodeIds = if (scopedNodeIds.isNotEmpty()) {
            scopedNodeIds + index.graph.edges
                .asSequence()
                .filter { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" }
                .filter { edge -> edge.fromNodeId in scopedNodeIds || edge.toNodeId in scopedNodeIds }
                .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
                .toSet()
        } else {
            relationshipNodeIds
        }
        val relationshipNodes = candidateNodes.filter { node -> node.id in fullGraphNodeIds }
        val inventoryOnlyNodes = candidateNodes.filter { node -> node.id !in fullGraphNodeIds }
        val packageDisplayContexts = computeDisplayContexts(relationshipNodes)
        val packageGraph = graphDocumentBuilder(index, relationshipNodes, true, AnalysisDisplayMode.ARCHITECTURE_GRAPH, request, packageDisplayContexts)
        val fullGraph = packageGraph.copy(
            edges = packageGraph.edges.filter { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" },
        )
        val visibleWindow = fullGraph.visibleWindow(
            policy = GraphViewportPolicy(
                maxVisibleNodes = request.viewport.maxVisibleNodes ?: viewportPolicy.maxVisibleNodes,
                maxVisibleEdges = request.viewport.maxVisibleEdges ?: viewportPolicy.maxVisibleEdges,
                enableOverflowSummary = viewportPolicy.enableOverflowSummary,
            ),
            seedNodeTypes = setOf(NodeType.PACKAGE, NodeType.RESOURCE, NodeType.LIBRARY),
            nodePriority = ::architectureNodePriority,
            edgePriority = ::architectureEdgePriority,
        )
        val visibleGraph = enrichGraph(visibleWindow.graph)
        val anchorNodeId = selectAnchorNodeId(visibleGraph)
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        return ArchitectureGraphResult(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = ArchitectureGraphSummary(
                moduleCount = visibleGraph.nodes.count { it.type == NodeType.MODULE },
                packageCount = visibleGraph.nodes.count { it.type == NodeType.PACKAGE },
                serviceCount = visibleGraph.nodes.count { it.type == NodeType.SERVICE },
                componentCount = visibleGraph.nodes.count { it.type == NodeType.COMPONENT },
                resourceCount = visibleGraph.nodes.count { it.type == NodeType.RESOURCE },
                layerCount = visibleGraph.nodes.count { it.type == NodeType.LAYER },
                libraryCount = visibleGraph.nodes.count { it.type == NodeType.LIBRARY },
                jdkCount = visibleGraph.nodes.count { it.metadata["architecture.node.kind"] == ArchitectureNodeKind.JDK.name },
                relationCount = visibleGraph.edges.size,
                classCount = index.symbolIndex.classesByQualifiedName.size,
                relationshipNodeCount = relationshipNodes.size,
                inventoryOnlyNodeCount = inventoryOnlyNodes.size,
                unconnectedPackageCount = inventoryOnlyNodes.count { it.kind == ArchitectureNodeKind.PACKAGE },
                unconnectedComponentCount = inventoryOnlyNodes.count { it.kind == ArchitectureNodeKind.COMPONENT },
                unconnectedServiceBoundaryCount = inventoryOnlyNodes.count { it.kind == ArchitectureNodeKind.SERVICE },
                unconnectedResourceCount = inventoryOnlyNodes.count { it.kind == ArchitectureNodeKind.RESOURCE },
                externalDependencyGroupCount = candidateNodes.count { it.kind == ArchitectureNodeKind.LIBRARY },
                jdkGroupCount = candidateNodes.count { it.kind == ArchitectureNodeKind.JDK },
                truncated = index.graph.truncated || visibleWindow.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                indexed = request.toSummary(
                    index = index,
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                    anchorNodeId = anchorNodeId,
                    scopedNodeCount = fullGraph.nodes.size,
                    candidateNodeCount = relationshipNodes.size,
                    candidateEdgeCount = fullGraph.edges.size,
                    hiddenNodeCount = hiddenNodeCount,
                    hiddenEdgeCount = hiddenEdgeCount,
                    truncated = index.graph.truncated || visibleWindow.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                    cacheState = cacheState,
                    freshness = freshness,
                ),
            ),
            projectionIndex = buildProjectionIndex(visibleGraph),
            presentation = buildPresentation(visibleGraph, fullGraph, anchorNodeId),
        )
    }
}
