package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.application.indexed.IndexedGraphCompleteness
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.requestClassDiagramRequest
import com.charmnight.linkgraph.application.indexed.toSummary
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.presentation.GraphHiddenBucketProjector
import com.charmnight.linkgraph.presentation.GraphPresentationControls
import com.charmnight.linkgraph.presentation.GraphPresentationLane
import com.charmnight.linkgraph.presentation.GraphPresentationLaneAxis
import com.charmnight.linkgraph.presentation.GraphPresentationTarget
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationExtractor
import com.charmnight.linkgraph.jvm.relation.ClassDiagramRelationRole
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.projection.projectedSourceEdgeIds
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

class ClassDiagramProjector(
    private val architectureProjector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(maxVisibleNodes = 48, maxVisibleEdges = 96),
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) {
    fun project(
        index: ArchitectureGraphIndex,
        scopeNodeId: String? = null,
        relationCompleteness: String = "COMPLETE",
        request: IndexedGraphRequest = requestClassDiagramRequest(scopeNodeId),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ClassDiagramViewDocument {
        val scopeIsClassLike = scopeNodeId?.let { nodeId -> index.node(nodeId)?.kind in classLikeKinds } == true
        val explicitScopedClassIds = scopeNodeId
            ?.takeUnless { scopeIsClassLike }
            ?.let(index::classesInScope)
            ?.mapTo(linkedSetOf()) { cls -> cls.id }
            .orEmpty()
        val anchorClassId = scopeNodeId
            ?.takeIf { nodeId -> index.node(nodeId)?.kind in classLikeKinds }
            ?: explicitScopedClassIds.firstOrNull()
            ?: defaultAnchorClassId(index)
        val scopedClassIds = if (explicitScopedClassIds.isNotEmpty()) {
            explicitScopeClassIdsWithExternalOneHop(index, explicitScopedClassIds)
        } else {
            classNeighborhoodIds(index, anchorClassId, request.classDiagram.neighborhoodLimit)
        }
        val neighborhoodCandidateTypeCount = if (explicitScopedClassIds.isEmpty()) {
            classNeighborhoodCandidateTypeCount(index, anchorClassId)
        } else {
            scopedClassIds.size
        }
        val neighborhoodTruncated = explicitScopedClassIds.isEmpty() &&
            neighborhoodCandidateTypeCount > scopedClassIds.size
        val nodes = index.graph.nodes.filter { node ->
            node.kind in classLikeKinds && node.id in scopedClassIds
        }
        val fullGraph = toUmlClassDiagramEdges(
            index,
            architectureProjector.graphDocument(
                index = index,
                nodes = nodes,
                includeClassEdges = true,
                viewMode = AnalysisDisplayMode.CLASS_DIAGRAM,
                request = request,
            ).withUmlClassMembers(index, request),
        ).withClassDiagramPresentationMetadata(anchorClassId ?: scopeNodeId)
        val primaryGraph = fullGraph
            .withoutSignatureOnlyNoiseNodes(anchorClassId ?: scopeNodeId)
            .readableClassDiagramProjection(anchorClassId ?: scopeNodeId)
        val visibleWindow = primaryGraph.visibleWindow(
            policy = request.classDiagramViewportPolicy(),
            anchorNodeId = anchorClassId ?: scopeNodeId,
            seedNodeTypes = setOf(NodeType.CLASS, NodeType.INTERFACE),
            nodePriority = ::classDiagramNodePriority,
            edgePriority = ::classDiagramEdgePriority,
        )
        val windowGraph = visibleWindow.graph
        val anchorNodeId = anchorClassId?.takeIf { nodeId -> windowGraph.nodes.any { it.id == nodeId } }
            ?: scopeNodeId?.takeIf { nodeId -> windowGraph.nodes.any { it.id == nodeId } }
            ?: windowGraph.nodes.firstOrNull { it.type.name == "CLASS" }?.id
            ?: windowGraph.nodes.firstOrNull()?.id
        val visibleGraphWithPresentation = windowGraph
            .withMissingClassDiagramPresentationMetadata(anchorNodeId)
            .aggregateParallelClassDiagramRelations()
        val hiddenCounts = graphProjectionHiddenCounts(
            visibleGraph = visibleGraphWithPresentation,
            fullGraph = fullGraph,
        )
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        val truncated = index.graph.truncated ||
            visibleWindow.truncated ||
            neighborhoodTruncated ||
            hiddenNodeCount > 0 ||
            hiddenEdgeCount > 0
        val effectiveRequest = request.copy(
            completeness = if (relationCompleteness == ClassDiagramFastIndex.RELATION_COMPLETENESS_PARTIAL) {
                IndexedGraphCompleteness.StructureOnly
            } else {
                request.completeness
            },
        )
        return ClassDiagramViewDocument(
            visibleGraph = visibleGraphWithPresentation,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = ClassDiagramSummary(
                classCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.CLASS.name },
                fieldCount = visibleGraphWithPresentation.nodes.sumOf { node -> node.metadata["uml.field.count"]?.toIntOrNull() ?: 0 },
                interfaceCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name },
                enumCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ENUM.name },
                annotationCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ANNOTATION.name },
                recordCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.RECORD.name },
                objectCount = visibleGraphWithPresentation.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.OBJECT.name },
                relationCount = visibleGraphWithPresentation.edges.size,
                spiProviderCount = 0,
                reflectionRelationCount = 0,
                relationCompleteness = relationCompleteness,
                scopeTypeCount = fullGraph.nodes.size,
                projectTypeCount = index.graph.nodes.count { node -> node.kind in classLikeKinds },
                projectClassCount = index.symbolIndex.classesByQualifiedName.values.count { symbol ->
                    symbol.kind == JvmClassKind.CLASS
                },
                scopeBasis = if (explicitScopedClassIds.isNotEmpty()) {
                    "EXPLICIT_SCOPE"
                } else {
                    "CLASS_NEIGHBORHOOD"
                },
                anchorTypeNodeId = anchorClassId,
                anchorTypeTitle = anchorClassId?.let(index::node)?.title,
                anchorTypeQualifiedName = anchorClassId?.let(index::node)?.qualifiedName,
                neighborhoodLimit = request.classDiagram.neighborhoodLimit,
                memberLimit = request.classDiagram.memberLimit,
                neighborhoodCandidateTypeCount = neighborhoodCandidateTypeCount,
                neighborhoodTruncated = neighborhoodTruncated,
                truncated = truncated,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                indexed = effectiveRequest.toSummary(
                    index = index,
                    visibleGraph = visibleGraphWithPresentation,
                    fullGraph = fullGraph,
                    anchorNodeId = anchorNodeId,
                    anchorTitle = anchorClassId?.let(index::node)?.title,
                    anchorQualifiedName = anchorClassId?.let(index::node)?.qualifiedName,
                    scopedNodeCount = fullGraph.nodes.size,
                    candidateNodeCount = neighborhoodCandidateTypeCount,
                    candidateEdgeCount = fullGraph.edges.size,
                    hiddenNodeCount = hiddenNodeCount,
                    hiddenEdgeCount = hiddenEdgeCount,
                    truncated = truncated,
                    cacheState = cacheState,
                    freshness = freshness,
                ),
            ),
            projectionIndex = classDiagramProjectionIndex(visibleGraphWithPresentation),
            presentation = classDiagramPresentation(
                visibleGraph = visibleGraphWithPresentation,
                fullGraph = fullGraph,
                anchorNodeId = anchorNodeId,
                fallbackAnchorNodeId = anchorClassId ?: scopeNodeId,
            ),
        )
    }

    private fun defaultAnchorClassId(index: ArchitectureGraphIndex): String? {
        val relationScoreByNodeId = index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in classLikeKinds }
            .associate { node -> node.id to classRelationScore(index, node.id) }
        return index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in classLikeKinds }
            .sortedWith(
                compareBy(
                    { node -> relationScoreByNodeId.getValue(node.id) == 0 },
                    { node -> -relationScoreByNodeId.getValue(node.id) },
                    { node -> classAnchorPriority(node.title) },
                    { node -> node.qualifiedName },
                    { node -> node.id },
                ),
            )
            .firstOrNull()
            ?.id
    }

    private fun classRelationScore(
        index: ArchitectureGraphIndex,
        classNodeId: String,
    ): Int =
        (index.incoming(classNodeId) + index.outgoing(classNodeId)).count { edge ->
            ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                index.node(edge.toNodeId)?.kind in classLikeKinds
        }

    private fun classNeighborhoodIds(
        index: ArchitectureGraphIndex,
        anchorClassId: String?,
        neighborhoodLimit: Int,
    ): Set<String> {
        val anchorId = anchorClassId ?: return emptySet()
        val limit = neighborhoodLimit.coerceAtLeast(1)
        val selected = linkedSetOf(anchorId)
        val edgeComparator = compareBy<com.charmnight.linkgraph.architecture.ArchitectureEdge>(
            { edge -> ClassDiagramRelationPolicy.priority(edge) },
            { edge -> edge.id },
        )
        (index.incoming(anchorId) + index.outgoing(anchorId))
            .asSequence()
            .filter { edge ->
                ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                    index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                    index.node(edge.toNodeId)?.kind in classLikeKinds
            }
            .sortedWith(edgeComparator)
            .forEach { edge ->
                for (candidateNodeId in listOf(edge.fromNodeId, edge.toNodeId)) {
                    if (selected.size >= limit) {
                        return@forEach
                    }
                    selected += candidateNodeId
                }
        }
        return selected
    }

    private fun classNeighborhoodCandidateTypeCount(
        index: ArchitectureGraphIndex,
        anchorClassId: String?,
    ): Int {
        val anchorId = anchorClassId ?: return 0
        val candidateIds = linkedSetOf(anchorId)
        (index.incoming(anchorId) + index.outgoing(anchorId))
            .asSequence()
            .filter { edge ->
                ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                    index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                    index.node(edge.toNodeId)?.kind in classLikeKinds
            }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .forEach(candidateIds::add)
        return candidateIds.size
    }

    private fun explicitScopeClassIdsWithExternalOneHop(
        index: ArchitectureGraphIndex,
        explicitClassIds: Set<String>,
    ): Set<String> {
        val selected = linkedSetOf<String>()
        selected += explicitClassIds
        explicitClassIds.forEach { classId ->
            (index.incoming(classId) + index.outgoing(classId))
                .asSequence()
                .filter { edge -> ClassDiagramRelationPolicy.participatesInClassDiagram(edge) }
                .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
                .filter { nodeId -> nodeId !in selected }
                .filter { nodeId -> index.node(nodeId)?.kind in classLikeKinds }
                .filter { nodeId ->
                    val classSymbol = index.findSymbol(nodeId) as? com.charmnight.linkgraph.jvm.index.JvmClassSymbol
                    classSymbol?.external == true || classSymbol?.library == true || classSymbol?.jdk == true
                }
                .forEach { nodeId -> selected += nodeId }
        }
        return selected
    }

    private fun toUmlClassDiagramEdges(
        index: ArchitectureGraphIndex,
        graph: GraphDocument,
    ): GraphDocument {
        return graph.copy(
            edges = graph.edges
                .filter { edge -> ClassDiagramRelationPolicy.participatesInClassDiagram(edge) }
                .mapNotNull { edge ->
                    val relationKind = ClassDiagramRelationPolicy.classify(edge, index) ?: return@mapNotNull null
                    val semanticLabel = edge.metadata[ClassDiagramRelationExtractor.LABEL_KEY]
                        ?: relationKind.label
                    edge.copy(
                        type = ClassDiagramRelationPolicy.edgeTypeFor(edge, relationKind),
                        label = semanticLabel,
                        metadata = edge.metadata + mapOf(
                            "uml.relation.kind" to relationKind.name,
                            "uml.relation.label" to semanticLabel,
                        ),
                    )
                },
        )
    }

    private fun GraphDocument.withClassDiagramPresentationMetadata(anchorNodeId: String?): GraphDocument {
        val anchorId = anchorNodeId
        val incomingToAnchor = anchorId
            ?.let { id -> edges.filter { edge -> edge.toNodeId == id }.mapTo(linkedSetOf(), GraphEdge::fromNodeId) }
            .orEmpty()
        val outgoingFromAnchor = anchorId
            ?.let { id -> edges.filter { edge -> edge.fromNodeId == id }.mapTo(linkedSetOf(), GraphEdge::toNodeId) }
            .orEmpty()
        val abstractionNodeIds = edges
            .filter { edge ->
                edge.metadata["uml.relation.kind"] in setOf(
                    UmlClassRelationKind.GENERALIZATION.name,
                    UmlClassRelationKind.REALIZATION.name,
                )
            }
            .mapTo(linkedSetOf(), GraphEdge::toNodeId)
        val outboundKindsByTarget = anchorId
            ?.let { id ->
                edges
                    .filter { edge -> edge.fromNodeId == id }
                    .groupBy(GraphEdge::toNodeId) { edge -> edge.metadata["uml.relation.kind"].orEmpty() }
            }
            .orEmpty()
        val outboundRolesByTarget = anchorId
            ?.let { id ->
                edges
                    .filter { edge -> edge.fromNodeId == id }
                    .groupBy(GraphEdge::toNodeId) { edge -> edge.metadata[ClassDiagramRelationExtractor.ROLE_KEY].orEmpty() }
            }
            .orEmpty()
        val nodeReasonById = anchorId
            ?.let { id -> classDiagramNodeReasons(edges, id) }
            .orEmpty()
        return copy(
            nodes = nodes.map { node ->
                val role = classDiagramRole(
                    node = node,
                    anchorNodeId = anchorId,
                    incomingToAnchor = incomingToAnchor,
                    outgoingFromAnchor = outgoingFromAnchor,
                    abstractionNodeIds = abstractionNodeIds,
                    outboundKinds = outboundKindsByTarget[node.id].orEmpty(),
                    outboundRoles = outboundRolesByTarget[node.id].orEmpty(),
                )
                node.copy(
                    metadata = node.metadata + role.toMetadata() + buildMap {
                        nodeReasonById[node.id]?.let { reason -> put("classDiagram.node.reason", reason) }
                    },
                )
            },
        )
    }

    private fun GraphDocument.withMissingClassDiagramPresentationMetadata(anchorNodeId: String?): GraphDocument =
        if (nodes.all { node -> node.metadata["presentation.role"] != null }) {
            this
        } else {
            withClassDiagramPresentationMetadata(anchorNodeId)
        }

    private fun GraphDocument.withoutSignatureOnlyNoiseNodes(anchorNodeId: String?): GraphDocument {
        val incidentEdgesByNodeId = buildMap<String, MutableList<GraphEdge>> {
            edges.forEach { edge ->
                getOrPut(edge.fromNodeId) { mutableListOf() } += edge
                getOrPut(edge.toNodeId) { mutableListOf() } += edge
            }
        }
        val hasPrimaryRelation = edges.any { edge -> !edge.isSignatureOnlyNoiseRelation() }
        if (!hasPrimaryRelation) {
            return this
        }
        val visibleNodeIds = nodes
            .asSequence()
            .filter { node ->
                node.id == anchorNodeId ||
                    incidentEdgesByNodeId[node.id]
                        .orEmpty()
                        .let { incidentEdges ->
                            incidentEdges.isEmpty() ||
                                incidentEdges.any { edge -> !edge.isSignatureOnlyNoiseRelation() }
                        }
            }
            .mapTo(linkedSetOf(), GraphNode::id)
        return copy(
            nodes = nodes.filter { node -> node.id in visibleNodeIds },
            edges = edges.filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds },
        )
    }

    private fun GraphDocument.readableClassDiagramProjection(anchorNodeId: String?): GraphDocument {
        val anchorNode = resolveReadableAnchorNode(anchorNodeId) ?: return this
        val nodeById = nodes.associateBy(GraphNode::id)
        val selectedNodeIds = linkedSetOf(anchorNode.id)
        val selectedEdges = mutableListOf<GraphEdge>()
        edges
            .asSequence()
            .filter { edge -> edge.fromNodeId in nodeById && edge.toNodeId in nodeById }
            .filter { edge -> edge.isReadableAnchorRelation(anchorNode.id) }
            .sortedWith(
                compareByDescending<GraphEdge> { edge -> edge.readableRelationPriority(anchorNode.id) }
                    .thenBy { edge -> edge.classDiagramRelationSortKey() },
            )
            .forEach { edge ->
                selectedNodeIds += edge.peerNodeId(anchorNode.id)
                selectedEdges += edge
            }
        if (selectedEdges.isEmpty()) {
            edges
                .asSequence()
                .filter { edge -> edge.fromNodeId in nodeById && edge.toNodeId in nodeById }
                .filter { edge -> edge.isAnchorRelation(anchorNode.id) }
                .filterNot { edge -> edge.isNoisyDefaultRelation() }
                .sortedWith(
                    compareByDescending<GraphEdge> { edge -> edge.readableRelationPriority(anchorNode.id) }
                        .thenBy { edge -> edge.classDiagramRelationSortKey() },
                )
                .forEach { edge ->
                    selectedNodeIds += edge.peerNodeId(anchorNode.id)
                    selectedEdges += edge
                }
        }
        if (selectedEdges.isEmpty()) {
            return copy(nodes = listOf(anchorNode), edges = emptyList())
        }
        val selectedNodes = listOf(anchorNode.id)
            .plus(selectedEdges.map { edge -> edge.peerNodeId(anchorNode.id) })
            .distinct()
            .mapNotNull(nodeById::get)
        val visibleNodeIds = selectedNodes.mapTo(linkedSetOf(), GraphNode::id)
        return copy(
            nodes = selectedNodes,
            edges = edges
                .filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds }
                .filter { edge -> edge.isReadableClassDiagramRelation() || edge.isFallbackVisibleRelation(anchorNode.id) }
                .sortedBy { edge -> edge.classDiagramRelationSortKey() },
        )
    }

    private fun GraphDocument.resolveReadableAnchorNode(anchorNodeId: String?): GraphNode? =
        anchorNodeId
            ?.let { nodeId -> nodes.firstOrNull { node -> node.id == nodeId } }
            ?: nodes.firstOrNull { node -> node.metadata["presentation.role"] == "ANCHOR" }
            ?: nodes.firstOrNull { node -> node.type == NodeType.CLASS }
            ?: nodes.firstOrNull()

    private fun GraphEdge.peerNodeId(anchorNodeId: String): String =
        if (fromNodeId == anchorNodeId) toNodeId else fromNodeId

    private fun GraphEdge.isAnchorRelation(anchorNodeId: String): Boolean =
        fromNodeId == anchorNodeId || toNodeId == anchorNodeId

    private fun GraphEdge.isReadableAnchorRelation(anchorNodeId: String): Boolean {
        if (!isAnchorRelation(anchorNodeId)) {
            return false
        }
        return isReadableClassDiagramRelation()
    }

    private fun GraphEdge.isReadableClassDiagramRelation(): Boolean {
        if (isNoisyDefaultRelation()) {
            return false
        }
        if (isHierarchyRelation() || isStructuralAssociationRelation()) {
            return true
        }
        return when (classDiagramRelationKind()) {
            ClassDiagramRelationRole.METHOD_CALL.name,
            ClassDiagramRelationRole.METHOD_RETURN.name,
            ClassDiagramRelationRole.METHOD_PARAMETER.name,
            -> relationWeight() >= MIN_READABLE_DEPENDENCY_WEIGHT
            "DEPENDENCY",
            "USES_TYPE",
            "INJECTS",
            -> relationWeight() >= 75
            else -> relationWeight() >= 80
        }
    }

    private fun GraphEdge.isFallbackVisibleRelation(anchorNodeId: String): Boolean =
        isAnchorRelation(anchorNodeId) && !isNoisyDefaultRelation()

    private fun GraphEdge.isNoisyDefaultRelation(): Boolean =
        classDiagramRelationKind() in noisyDefaultRelationKinds

    private fun GraphEdge.readableRelationPriority(anchorNodeId: String): Int {
        val directionBonus = if (toNodeId == anchorNodeId) 3 else 0
        val roleBonus = when {
            isHierarchyRelation() -> 30
            isStructuralAssociationRelation() -> 20
            else -> 0
        }
        return relationWeight() + roleBonus + directionBonus
    }

    private fun GraphEdge.classDiagramRelationSortKey(): ClassDiagramRelationSortKey =
        ClassDiagramRelationSortKey(
            priority = classDiagramEdgePriority(this),
            kind = classDiagramRelationKind(),
            label = label.orEmpty(),
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            id = id,
        )

    private fun GraphEdge.classDiagramRelationKind(): String =
        metadata[ClassDiagramRelationExtractor.ROLE_KEY]
            ?: metadata["uml.relation.kind"]
            ?: metadata["jvm.relation.kind"]
            ?: type.name

    private fun GraphEdge.classDiagramUmlRelationKind(): String =
        metadata["uml.relation.kind"]
            ?: when (classDiagramRelationRole()) {
                ClassDiagramRelationRole.EXTENDS -> UmlClassRelationKind.GENERALIZATION.name
                ClassDiagramRelationRole.IMPLEMENTS -> UmlClassRelationKind.REALIZATION.name
                ClassDiagramRelationRole.FIELD,
                ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER,
                -> UmlClassRelationKind.ASSOCIATION.name
                ClassDiagramRelationRole.METHOD_CALL,
                ClassDiagramRelationRole.METHOD_PARAMETER,
                ClassDiagramRelationRole.METHOD_RETURN,
                ClassDiagramRelationRole.THROWS,
                ClassDiagramRelationRole.LOCAL_TYPE,
                -> UmlClassRelationKind.DEPENDENCY.name
                null -> metadata["jvm.relation.kind"] ?: type.name
            }

    private fun GraphEdge.classDiagramRelationRole(): ClassDiagramRelationRole? =
        metadata[ClassDiagramRelationExtractor.ROLE_KEY]
            ?.let { raw -> ClassDiagramRelationRole.entries.firstOrNull { role -> role.name == raw } }

    private fun GraphEdge.isHierarchyRelation(): Boolean =
        classDiagramRelationKind() in hierarchyRelationKinds

    private fun GraphEdge.isStructuralAssociationRelation(): Boolean =
        classDiagramRelationKind() in structuralAssociationRelationKinds

    private fun GraphEdge.relationWeight(): Int =
        metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
            ?.toIntOrNull()
            ?: classDiagramRelationRole()?.baseWeight
            ?: when (classDiagramRelationKind()) {
                "GENERALIZATION",
                "EXTENDS",
                "REALIZATION",
                "IMPLEMENTS",
                -> 100
                "COMPOSITION",
                "AGGREGATION",
                "ASSOCIATION",
                "FIELD",
                "CONSTRUCTOR_PARAMETER",
                -> 90
                "METHOD_CALL" -> 72
                "METHOD_RETURN",
                "METHOD_PARAMETER",
                -> 58
                "DEPENDENCY",
                "USES_TYPE",
                "INJECTS",
                -> 48
                "THROWS",
                "LOCAL_TYPE",
                -> 20
                else -> 40
            }

    private fun GraphEdge.isSignatureOnlyNoiseRelation(): Boolean {
        val role = metadata[ClassDiagramRelationExtractor.ROLE_KEY] ?: return false
        val usedInBody = metadata[ClassDiagramRelationExtractor.USED_IN_BODY_KEY] == "true"
        val assignedToField = metadata[ClassDiagramRelationExtractor.FIELD_ASSIGNED_KEY] == "true"
        return when (role) {
            ClassDiagramRelationRole.METHOD_PARAMETER.name -> !usedInBody
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER.name -> !usedInBody && !assignedToField
            else -> false
        }
    }

    private fun GraphDocument.aggregateParallelClassDiagramRelations(): GraphDocument {
        val aggregatedEdges = edges
            .groupBy { edge -> edge.fromNodeId to edge.toNodeId }
            .values
            .map(::aggregateClassDiagramRelationGroup)
            .sortedBy { edge -> edge.classDiagramRelationSortKey() }
        return copy(edges = aggregatedEdges)
    }

    private fun aggregateClassDiagramRelationGroup(edges: List<GraphEdge>): GraphEdge {
        val sortedEdges = edges.sortedBy { edge -> edge.classDiagramRelationSortKey() }
        val primaryEdge = sortedEdges.first()
        if (sortedEdges.size == 1) {
            return primaryEdge.copy(label = primaryEdge.classDiagramDisplayLabel())
        }
        val sourceEdgeIds = sortedEdges.map(GraphEdge::id)
        val aggregateLabel = aggregateRelationLabel(sortedEdges)
        val aggregatePrimaryLabel = aggregatePrimaryRelationLabel(sortedEdges)
        val aggregateSecondaryLabels = aggregateSecondaryRelationLabels(sortedEdges)
        return primaryEdge.copy(
            id = aggregateRelationId(primaryEdge.fromNodeId, primaryEdge.toNodeId),
            label = aggregateLabel,
            metadata = primaryEdge.metadata + mapOf(
                "uml.relation.kind" to primaryEdge.classDiagramUmlRelationKind(),
                "uml.relation.label" to primaryEdge.classDiagramRelationLabel(),
                "uml.relation.aggregate.label" to aggregateLabel,
                "uml.relation.aggregate.primaryLabel" to aggregatePrimaryLabel,
                "uml.relation.aggregate.secondaryLabels" to aggregateSecondaryLabels.joinToString(";"),
                "uml.relation.aggregate.count" to sortedEdges.size.toString(),
                "uml.relation.aggregate.kinds" to sortedEdges.map { edge -> edge.classDiagramUmlRelationKind() }.distinct().joinToString(","),
                GraphProjectionMetadata.SourceEdges.UML_AGGREGATE_EDGE_IDS to sourceEdgeIds.joinToString(","),
            ),
        )
    }

    private fun aggregateRelationId(
        sourceNodeId: String,
        targetNodeId: String,
    ): String =
        "uml:relation:${sourceNodeId}->${targetNodeId}"

    private fun aggregateRelationLabel(edges: List<GraphEdge>): String {
        val primaryLabel = aggregatePrimaryRelationLabel(edges)
        val hiddenLabelCount = aggregateSecondaryRelationLabels(edges).size
        if (hiddenLabelCount <= 0) {
            return primaryLabel
        }
        return "$primaryLabel +$hiddenLabelCount"
    }

    private fun aggregatePrimaryRelationLabel(edges: List<GraphEdge>): String =
        aggregateRelationLabels(edges).firstOrNull()
            ?: edges.firstOrNull()?.classDiagramDisplayLabel().orEmpty()

    private fun aggregateSecondaryRelationLabels(edges: List<GraphEdge>): List<String> =
        aggregateRelationLabels(edges).drop(1)

    private fun aggregateRelationLabels(edges: List<GraphEdge>): List<String> {
        val labelSourceEdges = edges
            .filter { edge -> edge.metadata[ClassDiagramRelationExtractor.ROLE_KEY] != null }
            .takeIf(List<GraphEdge>::isNotEmpty)
            ?: edges
        return labelSourceEdges
            .sortedBy { edge -> edge.classDiagramRelationSortKey() }
            .map { edge -> edge.classDiagramDisplayLabel() }
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun GraphEdge.classDiagramDisplayLabel(): String {
        metadata["uml.relation.aggregate.label"]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        metadata[ClassDiagramRelationExtractor.LABEL_KEY]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        return classDiagramRelationLabel()
    }

    private fun GraphEdge.classDiagramRelationLabel(): String =
        metadata[ClassDiagramRelationExtractor.LABEL_KEY]?.trim()?.takeIf(String::isNotBlank)
            ?: metadata["uml.relation.label"]?.trim()?.takeIf(String::isNotBlank)
            ?: label?.trim()?.takeIf(String::isNotBlank)
            ?: classDiagramRelationKind()

    private fun classDiagramProjectionIndex(graph: GraphDocument): GraphProjectionIndex =
        GraphProjectionIndex(
            nodeMappings = graph.nodes.associate { node ->
                node.id to GraphProjectionNodeMapping(
                    projectedNodeId = node.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalNodeIds = listOf(node.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
            edgeMappings = graph.edges.associate { edge ->
                edge.id to GraphProjectionEdgeMapping(
                    projectedEdgeId = edge.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalEdgeIds = edge.projectedSourceEdgeIds(),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
        )

    private fun classDiagramRole(
        node: GraphNode,
        anchorNodeId: String?,
        incomingToAnchor: Set<String>,
        outgoingFromAnchor: Set<String>,
        abstractionNodeIds: Set<String>,
        outboundKinds: List<String>,
        outboundRoles: List<String>,
    ): ClassDiagramPresentationRole =
        when {
            node.id == anchorNodeId -> ClassDiagramPresentationRole("anchor", "ANCHOR", 30, compact = false)
            node.type == NodeType.INTERFACE ||
                node.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name ||
                node.metadata["jvm.class.abstract"] == "true" ||
                node.id in abstractionNodeIds -> ClassDiagramPresentationRole("abstraction", "INTERFACE", 10)
            node.id in incomingToAnchor -> ClassDiagramPresentationRole("caller", "CALLER", 20)
            node.id in outgoingFromAnchor && ClassDiagramRelationRole.METHOD_RETURN.name in outboundRoles ->
                ClassDiagramPresentationRole("output", "OUTPUT", 50)
            node.id in outgoingFromAnchor -> ClassDiagramPresentationRole("collaborator", "COLLABORATOR", 40)
            else -> ClassDiagramPresentationRole("collaborator", "TYPE", 45)
        }

    private fun classDiagramNodeReasons(
        edges: List<GraphEdge>,
        anchorNodeId: String,
    ): Map<String, String> {
        return edges
            .filter { edge -> edge.fromNodeId == anchorNodeId || edge.toNodeId == anchorNodeId }
            .groupBy { edge -> if (edge.fromNodeId == anchorNodeId) edge.toNodeId else edge.fromNodeId }
            .mapValues { (_, candidateEdges) ->
                candidateEdges
                    .maxWithOrNull(
                        compareBy<GraphEdge>(
                            { edge -> edge.metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]?.toIntOrNull() ?: 0 },
                            { edge -> edge.metadata[ClassDiagramRelationExtractor.LABEL_KEY].orEmpty() },
                        ),
                    )
                    ?.let(::classDiagramNodeReason)
                    .orEmpty()
            }
            .filterValues(String::isNotBlank)
    }

    private fun classDiagramNodeReason(edge: GraphEdge): String {
        val label = edge.metadata[ClassDiagramRelationExtractor.LABEL_KEY]
            ?: edge.label
            ?: return ""
        val memberName = edge.metadata[ClassDiagramRelationExtractor.MEMBER_NAME_KEY]
            ?.takeIf(String::isNotBlank)
        if (memberName != null && label.contains(memberName)) {
            return label
        }
        return listOfNotNull(label, memberName).joinToString(" ")
    }

    private fun classDiagramPresentation(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
        fallbackAnchorNodeId: String?,
    ): GraphViewPresentation {
        val targetNodeId = anchorNodeId ?: fallbackAnchorNodeId
        val targetNode = targetNodeId?.let { nodeId -> fullGraph.nodes.firstOrNull { it.id == nodeId } }
            ?: visibleGraph.nodes.firstOrNull()
        return GraphViewPresentation(
            target = GraphPresentationTarget(
                nodeId = targetNodeId,
                title = targetNode?.title.orEmpty(),
                subtitle = targetNode?.signature.orEmpty(),
                location = targetNode?.location,
            ),
            lanes = classDiagramPresentationLanes(),
            hiddenBuckets = hiddenBucketProjector.project(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                bucketForNode = { node -> node.metadata["presentation.laneId"] ?: "collaborator" },
                labelForBucket = ::classDiagramBucketLabel,
            ),
            controls = GraphPresentationControls(
                primaryScope = "",
                availableScopes = emptyList(),
            ),
        )
    }

    private data class ClassDiagramPresentationRole(
        val laneId: String,
        val role: String,
        val priority: Int,
        val compact: Boolean = true,
    ) {
        fun toMetadata(): Map<String, String> =
            mapOf(
                "presentation.role" to role,
                "presentation.laneId" to laneId,
                "presentation.priority" to priority.toString(),
                "presentation.compact" to compact.toString(),
            )
    }

    private fun GraphDocument.withUmlClassMembers(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
    ): GraphDocument {
        val fieldsByOwner = index.symbolIndex.fieldsByQualifiedName.values
            .groupBy(JvmFieldSymbol::ownerClassName)
        val methodsByOwner = index.symbolIndex.methodsBySignature.values
            .groupBy(JvmMethodSymbol::ownerClassName)
        return copy(
            nodes = nodes.map { node ->
                val qualifiedName = node.signature ?: node.metadata["architecture.qualifiedName"] ?: return@map node
                val classSymbol = index.findClass(qualifiedName)
                val fields = fieldsByOwner[qualifiedName].orEmpty().sortedBy(JvmFieldSymbol::qualifiedName)
                val methods = methodsByOwner[qualifiedName].orEmpty().sortedWith(
                    compareBy<JvmMethodSymbol>({ it.simpleName == "<init>" }, JvmMethodSymbol::signature),
                )
                node.withUmlClassMetadata(classSymbol, fields, methods, request.classDiagram.memberLimit)
            },
        )
    }

    private fun GraphNode.withUmlClassMetadata(
        classSymbol: com.charmnight.linkgraph.jvm.index.JvmClassSymbol?,
        fields: List<JvmFieldSymbol>,
        methods: List<JvmMethodSymbol>,
        memberLimit: Int,
    ): GraphNode {
        val visibleMemberLimit = memberLimit.coerceAtLeast(0)
        val visibleFields = fields.take(visibleMemberLimit).map(::umlFieldText)
        val visibleMethods = methods.take(visibleMemberLimit).map(::umlMethodText)
        val comment = classSymbol?.docComment?.trim()?.ifBlank { null }
        return copy(
            doc = comment ?: doc,
            metadata = metadata + buildMap {
                put("uml.kind", "CLASS_DIAGRAM")
                classSymbol?.let { symbol ->
                    put("jvm.class.abstract", symbol.abstract.toString())
                    put("jvm.class.kind", symbol.kind.name)
                }
                comment?.let { put("uml.comment", it) }
                put("uml.field.count", fields.size.toString())
                put("uml.method.count", methods.size.toString())
                put("uml.field.items", visibleFields.joinToString("\n"))
                put("uml.method.items", visibleMethods.joinToString("\n"))
                put("uml.field.hiddenCount", (fields.size - visibleFields.size).coerceAtLeast(0).toString())
                put("uml.method.hiddenCount", (methods.size - visibleMethods.size).coerceAtLeast(0).toString())
            },
        )
    }

    private fun umlFieldText(field: JvmFieldSymbol): String =
        listOfNotNull(field.simpleName, field.typeName?.let(::shortTypeName))
            .joinToString(": ")

    private fun umlMethodText(method: JvmMethodSymbol): String {
        val parameters = method.parameterTypes.joinToString(", ") { type -> shortTypeName(type) }
        val returnType = method.returnType?.let(::shortTypeName)
        val signature = "${method.simpleName}($parameters)"
        return returnType?.let { "$signature: $it" } ?: signature
    }

    private fun shortTypeName(typeName: String): String {
        val normalized = typeName.trim()
        if (normalized.isEmpty()) {
            return normalized
        }
        return normalized
            .replace(Regex("""\b([a-z_][\w$]*\.)+([A-Z][\w$]*)""")) { match ->
                match.groupValues[2]
            }
    }

    private fun classDiagramNodePriority(node: com.charmnight.linkgraph.model.GraphNode): Int =
        when (node.type) {
            NodeType.CLASS,
            NodeType.INTERFACE,
            -> 0
            NodeType.ENUM,
            NodeType.ANNOTATION,
            NodeType.RECORD,
            NodeType.OBJECT,
            -> 1
            else -> 2
        }

    private fun classDiagramEdgePriority(edge: com.charmnight.linkgraph.model.GraphEdge): Int =
        edge.metadata[ClassDiagramRelationExtractor.WEIGHT_KEY]
            ?.toIntOrNull()
            ?.let { weight -> -weight }
            ?: when (edge.metadata["uml.relation.kind"]) {
                UmlClassRelationKind.GENERALIZATION.name -> 0
                UmlClassRelationKind.REALIZATION.name -> 1
                UmlClassRelationKind.COMPOSITION.name -> 2
                UmlClassRelationKind.AGGREGATION.name -> 3
                UmlClassRelationKind.ASSOCIATION.name -> 4
                UmlClassRelationKind.DEPENDENCY.name -> 5
                else -> 6
            }

    private fun classAnchorPriority(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.endsWith("action") -> 0
            lower.endsWith("controller") -> 1
            lower.endsWith("service") -> 2
            lower.endsWith("workflow") -> 3
            lower.endsWith("projector") -> 4
            else -> 5
        }
    }

    private fun IndexedGraphRequest.classDiagramViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: viewportPolicy.maxVisibleNodes,
            maxVisibleEdges = viewport.maxVisibleEdges ?: viewportPolicy.maxVisibleEdges,
        )

    private companion object {
        private val classLikeKinds = setOf(
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
        )
        private const val MIN_READABLE_DEPENDENCY_WEIGHT = 55
        private val hierarchyRelationKinds = setOf(
            ClassDiagramRelationRole.EXTENDS.name,
            ClassDiagramRelationRole.IMPLEMENTS.name,
            UmlClassRelationKind.GENERALIZATION.name,
            UmlClassRelationKind.REALIZATION.name,
            "EXTENDS",
            "IMPLEMENTS",
        )
        private val structuralAssociationRelationKinds = setOf(
            ClassDiagramRelationRole.FIELD.name,
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER.name,
            UmlClassRelationKind.COMPOSITION.name,
            UmlClassRelationKind.AGGREGATION.name,
            UmlClassRelationKind.ASSOCIATION.name,
            "FIELD",
            "CONSTRUCTOR_PARAMETER",
        )
        private val noisyDefaultRelationKinds = setOf(
            ClassDiagramRelationRole.LOCAL_TYPE.name,
            ClassDiagramRelationRole.THROWS.name,
            "LOCAL_TYPE",
            "THROWS",
        )
        private data class ClassDiagramRelationSortKey(
            val priority: Int,
            val kind: String,
            val label: String,
            val fromNodeId: String,
            val toNodeId: String,
            val id: String,
        ) : Comparable<ClassDiagramRelationSortKey> {
            override fun compareTo(other: ClassDiagramRelationSortKey): Int =
                compareValuesBy(
                    this,
                    other,
                    ClassDiagramRelationSortKey::priority,
                    ClassDiagramRelationSortKey::kind,
                    ClassDiagramRelationSortKey::label,
                    ClassDiagramRelationSortKey::fromNodeId,
                    ClassDiagramRelationSortKey::toNodeId,
                    ClassDiagramRelationSortKey::id,
                )
        }

        private fun classDiagramPresentationLanes(): List<GraphPresentationLane> =
            listOf(
                GraphPresentationLane("abstraction", "抽象与接口", GraphPresentationLaneAxis.ZONE, 10, "INTERFACE"),
                GraphPresentationLane("caller", "调用方", GraphPresentationLaneAxis.ZONE, 20, "CALLER"),
                GraphPresentationLane("anchor", "当前类", GraphPresentationLaneAxis.ZONE, 30, "ANCHOR"),
                GraphPresentationLane("collaborator", "协作对象", GraphPresentationLaneAxis.ZONE, 40, "COLLABORATOR"),
                GraphPresentationLane("output", "输出类型", GraphPresentationLaneAxis.ZONE, 50, "OUTPUT"),
            )

        private fun classDiagramBucketLabel(bucket: String): String =
            when (bucket) {
                "abstraction" -> "抽象与接口"
                "caller" -> "调用方"
                "anchor" -> "当前类"
                "collaborator" -> "协作对象"
                "output" -> "输出类型"
                else -> bucket
            }
    }
}
