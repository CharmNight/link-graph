package com.charmnight.linkgraph.testing

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
import com.charmnight.linkgraph.ui.view.FactGraphSummary
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartSummary
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationSummary
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun assertFactGraphViewDataContract(
    view: FactGraphViewDocument,
    label: String = "FactGraphView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertFactGraphSummary(view.summary, view.visibleGraph, view.fullGraph, view.anchorNodeId, label)
}

internal fun assertFlowchartViewDataContract(
    view: FlowchartViewDocument,
    label: String = "FlowchartView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertFlowchartSummary(view.summary, view.visibleGraph, view.fullGraph, label)
}

internal fun assertResourceRelationViewDataContract(
    view: ResourceRelationViewDocument,
    label: String = "ResourceRelationView",
) {
    assertGraphDocumentDataContract(view.visibleGraph, "$label.visibleGraph")
    assertGraphDocumentDataContract(view.fullGraph, "$label.fullGraph")
    assertVisibleGraphBackedByFullGraph(view.visibleGraph, view.fullGraph, label)
    assertAnchorResolves(view.anchorNodeId, view.visibleGraph, view.fullGraph, label)
    assertProjectionIndexDataContract(view.visibleGraph, view.fullGraph, view.projectionIndex, label)
    assertResourceRelationSummary(view.summary, view.visibleGraph, label)
}

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

internal fun assertGraphDocumentDataContract(
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
        if (node.id !in fullNodeIds) {
            val aliases = projectedAliasNodeIds(node)
            assertTrue(
                aliases.isNotEmpty() && aliases.all { alias -> alias in fullNodeIds },
                "$label visible node ${node.id} is neither a full node nor a resolvable projection alias.",
            )
        }
    }
    visibleGraph.edges.forEach { edge ->
        if (edge.id !in fullEdgeIds) {
            assertTrue(
                edge.metadata["flowchart.projection.mode"] == "READABLE" ||
                    edge.metadata["flow.synthetic"] == "true" ||
                    edge.metadata["flowchart.synthetic"] != null,
                "$label visible edge ${edge.id} is not present in full graph and has no projection metadata.",
            )
        }
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
    val aliasResolved = visibleGraph.nodes.any { node -> anchor in projectedAliasNodeIds(node) }
    assertTrue(
        anchor in visibleNodeIds || anchor in fullNodeIds || aliasResolved,
        "$label anchor $anchor does not resolve to visible or full graph data.",
    )
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

    assertEquals(visibleNodeIds, projectionIndex.nodeMappings.keys, "$label projection node mapping keys must match visible nodes.")
    assertEquals(visibleEdgeIds, projectionIndex.edgeMappings.keys, "$label projection edge mapping keys must match visible edges.")

    visibleGraph.nodes.forEach { node ->
        val mapping = assertNotNull(projectionIndex.nodeMappings[node.id], "$label missing node projection for ${node.id}.")
        assertEquals(node.id, mapping.projectedNodeId, "$label node mapping projected id mismatch for ${node.id}.")
        when (mapping.mappingKind) {
            GraphProjectionMappingKind.EXACT,
            GraphProjectionMappingKind.MERGED_ALIAS,
            GraphProjectionMappingKind.PATH_ALIAS,
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            -> {
                assertTrue(mapping.canonicalNodeIds.isNotEmpty(), "$label node ${node.id} has no canonical node ids.")
                assertTrue(
                    mapping.canonicalNodeIds.all { canonicalId -> canonicalId in fullNodeIds },
                    "$label node ${node.id} has canonical ids outside full graph: ${mapping.canonicalNodeIds}",
                )
            }
            GraphProjectionMappingKind.OVERFLOW_READONLY -> {
                assertTrue(mapping.canonicalNodeIds.isEmpty(), "$label overflow node ${node.id} should not claim canonical nodes.")
            }
        }
    }

    visibleGraph.edges.forEach { edge ->
        val mapping = assertNotNull(projectionIndex.edgeMappings[edge.id], "$label missing edge projection for ${edge.id}.")
        assertEquals(edge.id, mapping.projectedEdgeId, "$label edge mapping projected id mismatch for ${edge.id}.")
        when (mapping.mappingKind) {
            GraphProjectionMappingKind.EXACT -> {
                assertTrue(mapping.canonicalEdgeIds.isNotEmpty(), "$label exact edge ${edge.id} has no canonical edge ids.")
                assertTrue(
                    mapping.canonicalEdgeIds.all { canonicalId -> canonicalId in fullEdgeIds },
                    "$label edge ${edge.id} has canonical ids outside full graph: ${mapping.canonicalEdgeIds}",
                )
            }
            GraphProjectionMappingKind.MERGED_ALIAS,
            GraphProjectionMappingKind.PATH_ALIAS,
            GraphProjectionMappingKind.SYNTHETIC_READONLY,
            GraphProjectionMappingKind.OVERFLOW_READONLY,
            -> {
                assertTrue(
                    mapping.canonicalEdgeIds.isEmpty() || mapping.canonicalEdgeIds.all { canonicalId -> canonicalId in fullEdgeIds },
                    "$label readonly edge ${edge.id} has invalid canonical edge ids: ${mapping.canonicalEdgeIds}",
                )
            }
        }
    }
}

private fun assertFactGraphSummary(
    summary: FactGraphSummary,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    anchorNodeId: String?,
    label: String,
) {
    assertEquals(visibleGraph.nodes.size, summary.visibleNodeCount, "$label visibleNodeCount mismatch.")
    assertEquals(fullGraph.nodes.size, summary.fullNodeCount, "$label fullNodeCount mismatch.")
    val anchorTitle = anchorNodeId?.let { id ->
        fullGraph.nodes.firstOrNull { it.id == id }?.title ?: visibleGraph.nodes.firstOrNull { it.id == id }?.title
    }
    assertEquals(anchorTitle, summary.anchorTitle, "$label anchorTitle mismatch.")
}

private fun assertFlowchartSummary(
    summary: FlowchartSummary,
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    label: String,
) {
    val hiddenNodeCount = (fullGraph.nodes.map(GraphNode::id).toSet() - visibleGraph.nodes.map(GraphNode::id).toSet()).size
    val hiddenEdgeCount = (fullGraph.edges.map(GraphEdge::id).toSet() - visibleGraph.edges.map(GraphEdge::id).toSet()).size
    assertEquals(visibleGraph.nodes.size, summary.nodeCount, "$label nodeCount mismatch.")
    assertEquals(fullGraph.nodes.size, summary.fullNodeCount, "$label fullNodeCount mismatch.")
    assertEquals(fullGraph.edges.size, summary.fullEdgeCount, "$label fullEdgeCount mismatch.")
    assertEquals(hiddenNodeCount, summary.hiddenNodeCount, "$label hiddenNodeCount mismatch.")
    assertEquals(hiddenEdgeCount, summary.hiddenEdgeCount, "$label hiddenEdgeCount mismatch.")
    assertEquals(hiddenNodeCount > 0 || hiddenEdgeCount > 0, summary.truncated, "$label truncated mismatch.")
    assertEquals(
        visibleGraph.nodes.count { node -> resolveFlowchartKindForContract(node) == "DECISION" },
        summary.branchCount,
        "$label branchCount mismatch.",
    )
    assertEquals(
        visibleGraph.edges.count { edge -> edge.label?.trim()?.uppercase() == "EXCEPTION" },
        summary.exceptionPathCount,
        "$label exceptionPathCount mismatch.",
    )
    assertEquals(
        visibleGraph.nodes.count { node -> node.metadata["flow.incomplete"] == "true" },
        summary.incompleteNodeCount,
        "$label incompleteNodeCount mismatch.",
    )
    assertEquals(
        visibleGraph.edges.count { edge -> edge.metadata["flow.incomplete"] == "true" },
        summary.incompleteEdgeCount,
        "$label incompleteEdgeCount mismatch.",
    )
    assertEquals(
        summary.incompleteNodeCount > 0 || summary.incompleteEdgeCount > 0,
        summary.semanticallyIncomplete,
        "$label semanticallyIncomplete mismatch.",
    )
    assertEquals(
        visibleGraph.edges.count { edge -> edge.metadata["flow.synthetic"] == "true" },
        summary.syntheticEdgeCount,
        "$label syntheticEdgeCount mismatch.",
    )
}

private fun assertResourceRelationSummary(
    summary: ResourceRelationSummary,
    visibleGraph: GraphDocument,
    label: String,
) {
    val expectedLaneCounts = visibleGraph.nodes
        .groupingBy { node -> node.metadata["resource.lane"] ?: "CODE" }
        .eachCount()
        .toSortedMap()
    assertEquals(visibleGraph.nodes.size, summary.visibleNodeCount, "$label visibleNodeCount mismatch.")
    assertEquals(expectedLaneCounts, summary.laneCounts, "$label laneCounts mismatch.")
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
    assertTrue(summary.classCount >= 0, "$label classCount must be non-negative.")
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
    assertEquals(
        visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name },
        summary.interfaceCount,
        "$label interfaceCount mismatch.",
    )
    assertEquals(visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ENUM.name }, summary.enumCount, "$label enumCount mismatch.")
    assertEquals(
        visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ANNOTATION.name },
        summary.annotationCount,
        "$label annotationCount mismatch.",
    )
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
        assertTrue(hunk.oldStartLine == null || hunk.oldStartLine > 0, "$label hunk oldStartLine must be positive.")
        assertTrue(hunk.newStartLine == null || hunk.newStartLine > 0, "$label hunk newStartLine must be positive.")
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
        assertTrue(symbol.qualifiedName.isNotBlank(), "$label baseline-only symbol must expose qualifiedName.")
    }
    view.relatedTests.forEach { test ->
        assertTrue(test.symbolId in fullNodeIds, "$label related test ${test.symbolId} must be present in full graph.")
        assertTrue(test.reason.isNotBlank(), "$label related test ${test.symbolId} must expose reason.")
    }
    view.evidenceSnippets.forEach { snippet ->
        assertTrue(snippet.title.isNotBlank(), "$label evidence snippet must expose title.")
        assertTrue(snippet.kind.isNotBlank(), "$label evidence snippet must expose kind.")
        assertTrue(
            !snippet.snippet.isNullOrBlank() || !snippet.unavailableReason.isNullOrBlank(),
            "$label evidence snippet must expose either text or unavailable reason.",
        )
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
    assertTrue(hiddenNodeCount >= 0, "$label hiddenNodeCount must be non-negative.")
    assertTrue(hiddenEdgeCount >= 0, "$label hiddenEdgeCount must be non-negative.")
    if (hiddenNodeCount > 0 || hiddenEdgeCount > 0) {
        assertTrue(truncated, "$label truncated must be true when hidden data exists.")
    }
}

private fun resolveFlowchartKindForContract(node: GraphNode): String {
    val flowKind = node.metadata["flow.kind"]?.trim()?.uppercase()
    return when {
        node.type == NodeType.FLOW_SCOPE && flowKind in setOf("IF", "SWITCH", "FOREACH", "FOR", "WHILE", "DO_WHILE") -> "DECISION"
        node.type == NodeType.MERGE -> "MERGE"
        node.type == NodeType.TERMINAL -> "TERMINAL"
        else -> node.metadata["flowchart.kind"] ?: "PROCESS"
    }
}

private fun projectedAliasNodeIds(node: GraphNode): List<String> =
    node.metadata["flowchart.projectedFromNodeIds"]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()

private fun reviewHunkKey(hunk: ReviewGraphChangedHunk): String =
    listOf(
        hunk.filePath,
        hunk.oldFilePath.orEmpty(),
        hunk.newFilePath.orEmpty(),
        hunk.header,
        hunk.oldStartLine?.toString().orEmpty(),
        hunk.newStartLine?.toString().orEmpty(),
    ).joinToString("|")
