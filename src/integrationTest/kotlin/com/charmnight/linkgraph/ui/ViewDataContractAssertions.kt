package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.architecture.view.ArchitectureGraphSummary
import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.architecture.view.ClassDiagramSummary
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.review.ReviewGraphChangedFile
import com.charmnight.linkgraph.review.ReviewGraphChangedHunk
import com.charmnight.linkgraph.review.ReviewGraphSummary
import com.charmnight.linkgraph.review.ReviewGraphViewDocument
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun assertArchitectureGraphViewDataContract(
    view: ArchitectureGraphViewDocument,
    label: String = "ArchitectureGraphView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertArchitectureGraphSummary(view.summary, view.visibleGraph, view.fullGraph, label)
}

internal fun assertClassDiagramViewDataContract(
    view: ClassDiagramViewDocument,
    label: String = "ClassDiagramView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertClassDiagramSummary(view.summary, view.visibleGraph, view.fullGraph, label)
}

internal fun assertReviewGraphViewDataContract(
    view: ReviewGraphViewDocument,
    label: String = "ReviewGraphView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertReviewGraphSummary(view.summary, view.fullGraph, label)
    assertReviewGraphDiffDetails(view.changedFiles, view.changedHunks, view.unmatchedHunks, label)
    assertReviewGraphEvidenceDetails(view, label)
}

private fun assertGraphDocumentDataContract(
    graph: GraphDocument,
    label: String,
) {
    val nodeIds = graph.nodes.map(GraphNode::id)
    val edgeIds = graph.edges.map(GraphEdge::id)
    assertEquals(nodeIds.distinct(), nodeIds, "$label has duplicate node ids.")
    assertEquals(edgeIds.distinct(), edgeIds, "$label has duplicate edge ids.")
    val nodeIdSet = nodeIds.toSet()
    graph.edges.forEach { edge ->
        assertTrue(edge.fromNodeId in nodeIdSet, "$label edge ${edge.id} source ${edge.fromNodeId} is missing.")
        assertTrue(edge.toNodeId in nodeIdSet, "$label edge ${edge.id} target ${edge.toNodeId} is missing.")
    }
}

private fun assertVisibleGraphBackedByFullGraph(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    val fullNodeIds = fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
    visibleGraph.nodes.forEach { node ->
        assertTrue(node.id in fullNodeIds, "$label visible node ${node.id} is not present in full graph.")
    }
    visibleGraph.edges.forEach { edge ->
        assertTrue(edge.id in fullEdgeIds, "$label visible edge ${edge.id} is not present in full graph.")
    }
}

private fun assertAnchorResolves(
    anchorNodeId: String?,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    if (visibleGraph.nodes.isEmpty() && fullGraph.nodes.isEmpty()) {
        assertEquals(null, anchorNodeId, "$label empty graph must not expose an anchor.")
        return
    }
    val anchor = assertNotNull(anchorNodeId, "$label non-empty graph must expose an anchor.")
    val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    val fullNodeIds = fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    assertTrue(anchor in visibleNodeIds || anchor in fullNodeIds, "$label anchor $anchor does not resolve to graph data.")
}

private fun assertProjectionIndexDataContract(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    projectionIndex: GraphProjectionIndex,
    label: String,
) {
    val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
    val fullNodeIds = fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
    assertEquals(visibleNodeIds, projectionIndex.nodeMappings.keys, "$label projection node keys must match visible nodes.")
    assertEquals(visibleEdgeIds, projectionIndex.edgeMappings.keys, "$label projection edge keys must match visible edges.")
    visibleNodeIds.forEach { nodeId ->
        val mapping = assertNotNull(projectionIndex.nodeMappings[nodeId], "$label missing node projection for $nodeId.")
        assertEquals(nodeId, mapping.projectedNodeId, "$label node projection id mismatch for $nodeId.")
        if (mapping.mappingKind != GraphProjectionMappingKind.OVERFLOW_READONLY) {
            assertTrue(mapping.canonicalNodeIds.isNotEmpty(), "$label node $nodeId has no canonical ids.")
            assertTrue(mapping.canonicalNodeIds.all { it in fullNodeIds }, "$label node $nodeId has canonical ids outside full graph.")
        }
    }
    visibleEdgeIds.forEach { edgeId ->
        val mapping = assertNotNull(projectionIndex.edgeMappings[edgeId], "$label missing edge projection for $edgeId.")
        assertEquals(edgeId, mapping.projectedEdgeId, "$label edge projection id mismatch for $edgeId.")
        assertTrue(mapping.canonicalEdgeIds.isEmpty() || mapping.canonicalEdgeIds.all { it in fullEdgeIds })
    }
}

private fun assertArchitectureGraphSummary(
    summary: ArchitectureGraphSummary,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    assertEquals(visibleGraph.nodes.count { it.type == NodeType.MODULE }, summary.moduleCount, "$label moduleCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.type == NodeType.PACKAGE }, summary.packageCount, "$label packageCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.type == NodeType.SERVICE }, summary.serviceCount, "$label serviceCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.type == NodeType.RESOURCE }, summary.resourceCount, "$label resourceCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.type == NodeType.LAYER }, summary.layerCount, "$label layerCount mismatch.")
    assertEquals(visibleGraph.edges.size, summary.relationCount, "$label relationCount mismatch.")
    assertHiddenCounts(summary.hiddenNodeCount, summary.hiddenEdgeCount, summary.truncated, visibleGraph, fullGraph, label)
}

private fun assertClassDiagramSummary(
    summary: ClassDiagramSummary,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.CLASS.name }, summary.classCount, "$label classCount mismatch.")
    assertEquals(visibleGraph.nodes.sumOf { it.metadata["uml.field.count"]?.toIntOrNull() ?: 0 }, summary.fieldCount, "$label fieldCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name }, summary.interfaceCount, "$label interfaceCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ENUM.name }, summary.enumCount, "$label enumCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ANNOTATION.name }, summary.annotationCount, "$label annotationCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.RECORD.name }, summary.recordCount, "$label recordCount mismatch.")
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.OBJECT.name }, summary.objectCount, "$label objectCount mismatch.")
    assertEquals(visibleGraph.edges.size, summary.relationCount, "$label relationCount mismatch.")
    assertEquals(
        visibleGraph.edges.count { edge ->
            edge.metadata["jvm.relation.kind"] in setOf(
                JvmRelationKind.SPI_PROVIDES.name,
                JvmRelationKind.SERVICE_LOADER_LOADS.name,
            )
        },
        summary.spiProviderCount,
        "$label spiProviderCount mismatch.",
    )
    assertEquals(
        visibleGraph.edges.count { edge -> edge.metadata["jvm.relation.kind"] == JvmRelationKind.REFLECTS_TO.name },
        summary.reflectionRelationCount,
        "$label reflectionRelationCount mismatch.",
    )
    assertHiddenCounts(summary.hiddenNodeCount, summary.hiddenEdgeCount, summary.truncated, visibleGraph, fullGraph, label)
}

private fun assertReviewGraphSummary(
    summary: ReviewGraphSummary,
    fullGraph: GraphDocument,
    label: String,
) {
    assertEquals(fullGraph.nodes.count { it.metadata["review.role"] == "CHANGED" }, summary.changedSymbolCount, "$label changedSymbolCount mismatch.")
    assertEquals(fullGraph.nodes.count { it.metadata["review.role"] == "UPSTREAM" }, summary.upstreamCount, "$label upstreamCount mismatch.")
    assertEquals(fullGraph.nodes.count { it.metadata["review.role"] == "DOWNSTREAM" }, summary.downstreamCount, "$label downstreamCount mismatch.")
    assertEquals(fullGraph.nodes.count { it.metadata["review.role"] == "RELATED_TEST" }, summary.relatedTestCount, "$label relatedTestCount mismatch.")
    assertTrue(summary.affectedPackageCount >= 0, "$label affectedPackageCount must be non-negative.")
    assertTrue(summary.affectedModuleCount >= 0, "$label affectedModuleCount must be non-negative.")
    assertTrue(summary.evidenceRefCount >= 0, "$label evidenceRefCount must be non-negative.")
}

private fun assertReviewGraphDiffDetails(
    changedFiles: List<ReviewGraphChangedFile>,
    changedHunks: List<ReviewGraphChangedHunk>,
    unmatchedHunks: List<ReviewGraphChangedHunk>,
    label: String,
) {
    changedFiles.forEach { file ->
        assertTrue(file.oldPath != null || file.newPath != null, "$label changed file must expose an old or new path.")
        assertTrue(file.hunkCount >= 0, "$label changed file hunkCount must be non-negative.")
    }
    changedHunks.forEach { hunk ->
        assertTrue(hunk.filePath.isNotBlank(), "$label changed hunk must expose filePath.")
        assertTrue(hunk.header.isNotBlank(), "$label changed hunk must expose header.")
    }
    assertEquals(
        changedHunks.filter { hunk -> hunk.matchedSymbolIds.isEmpty() }.map(::reviewHunkKey),
        unmatchedHunks.map(::reviewHunkKey),
        "$label unmatchedHunks must be the unmatched subset of changedHunks.",
    )
}

private fun assertReviewGraphEvidenceDetails(
    view: ReviewGraphViewDocument,
    label: String,
) {
    val fullNodeIds = view.fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
    view.baselineOnlySymbols.forEach { symbol ->
        assertTrue(symbol.symbolId in fullNodeIds, "$label baseline-only symbol ${symbol.symbolId} must be present in full graph.")
    }
    view.relatedTests.forEach { test ->
        assertTrue(test.symbolId in fullNodeIds, "$label related test ${test.symbolId} must be present in full graph.")
    }
    view.evidenceSnippets.forEach { snippet ->
        assertTrue(!snippet.snippet.isNullOrBlank() || !snippet.unavailableReason.isNullOrBlank())
    }
}

private fun assertHiddenCounts(
    hiddenNodeCount: Int,
    hiddenEdgeCount: Int,
    truncated: Boolean,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    val minimumHiddenNodeCount = (fullGraph.nodes.map(GraphNode::id).toSet() - visibleGraph.nodes.map(GraphNode::id).toSet()).size
    val minimumHiddenEdgeCount = (fullGraph.edges.map(GraphEdge::id).toSet() - visibleGraph.edges.map(GraphEdge::id).toSet()).size
    assertTrue(hiddenNodeCount >= minimumHiddenNodeCount, "$label hiddenNodeCount is lower than actual hidden nodes.")
    assertTrue(hiddenEdgeCount >= minimumHiddenEdgeCount, "$label hiddenEdgeCount is lower than actual hidden edges.")
    if (hiddenNodeCount > 0 || hiddenEdgeCount > 0) {
        assertTrue(truncated, "$label truncated must be true when hidden data exists.")
    }
}

private fun reviewHunkKey(hunk: ReviewGraphChangedHunk): String =
    listOf(
        hunk.filePath,
        hunk.oldFilePath.orEmpty(),
        hunk.newFilePath.orEmpty(),
        hunk.header,
        hunk.oldStartLine?.toString().orEmpty(),
        hunk.newStartLine?.toString().orEmpty(),
    ).joinToString("|")
