package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.model.GraphDocument

data class ProjectStructureRelationGroup(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val displayRelationKind: String,
    val displayRelation: String,
    val relationKinds: List<String> = emptyList(),
    val count: Int = 0,
    val confidence: String = "UNKNOWN",
    val sourceRelationIds: List<String> = emptyList(),
    val sampleEvidenceRefs: List<String> = emptyList(),
    val defaultVisible: Boolean = false,
    val hiddenReason: String? = null,
)

data class ArchitectureGraphSummary(
    val moduleCount: Int = 0,
    val packageCount: Int = 0,
    val serviceCount: Int = 0,
    val componentCount: Int = 0,
    val resourceCount: Int = 0,
    val layerCount: Int = 0,
    val libraryCount: Int = 0,
    val jdkCount: Int = 0,
    val relationCount: Int = 0,
    val classCount: Int = 0,
    val relationshipNodeCount: Int = 0,
    val inventoryOnlyNodeCount: Int = 0,
    val unconnectedPackageCount: Int = 0,
    val unconnectedComponentCount: Int = 0,
    val unconnectedServiceBoundaryCount: Int = 0,
    val unconnectedResourceCount: Int = 0,
    val externalDependencyGroupCount: Int = 0,
    val jdkGroupCount: Int = 0,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val indexed: IndexedGraphSummary? = null,
    val projectStructureRelationGroups: List<ProjectStructureRelationGroup> = emptyList(),
)

data class ArchitectureGraphResult(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ArchitectureGraphSummary = ArchitectureGraphSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val presentation: GraphViewPresentation = GraphViewPresentation(),
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
    val relationCompleteness: String = "COMPLETE",
    val scopeTypeCount: Int = 0,
    val projectTypeCount: Int = 0,
    val projectClassCount: Int = 0,
    val scopeBasis: String = "CLASS_NEIGHBORHOOD",
    val anchorTypeNodeId: String? = null,
    val anchorTypeTitle: String? = null,
    val anchorTypeQualifiedName: String? = null,
    val neighborhoodLimit: Int = 0,
    val memberLimit: Int = 0,
    val neighborhoodCandidateTypeCount: Int = 0,
    val neighborhoodTruncated: Boolean = false,
    val truncated: Boolean = false,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val indexed: IndexedGraphSummary? = null,
)

data class ClassDiagramResult(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ClassDiagramSummary = ClassDiagramSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val presentation: GraphViewPresentation = GraphViewPresentation(),
)
