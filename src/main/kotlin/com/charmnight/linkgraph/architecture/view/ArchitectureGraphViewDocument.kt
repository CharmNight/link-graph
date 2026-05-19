package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument

data class ArchitectureGraphSummary(
    val moduleCount: Int = 0,
    val packageCount: Int = 0,
    val serviceCount: Int = 0,
    val resourceCount: Int = 0,
    val layerCount: Int = 0,
    val relationCount: Int = 0,
    val classCount: Int = 0,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
)

data class ArchitectureGraphViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ArchitectureGraphSummary = ArchitectureGraphSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)

data class ClassDiagramSummary(
    val classCount: Int = 0,
    val fieldCount: Int = 0,
    val interfaceCount: Int = 0,
    val enumCount: Int = 0,
    val annotationCount: Int = 0,
    val recordCount: Int = 0,
    val objectCount: Int = 0,
    val relationCount: Int = 0,
    val spiProviderCount: Int = 0,
    val reflectionRelationCount: Int = 0,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
)

data class ClassDiagramViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ClassDiagramSummary = ClassDiagramSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)
