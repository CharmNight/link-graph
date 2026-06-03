package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureEdge
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.architecture.ProjectStructureRelationGroup
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.indexedEdgeMetadata
import com.charmnight.linkgraph.application.indexed.indexedNodeMetadata
import com.charmnight.linkgraph.application.indexed.requestArchitectureGraphRequest
import com.charmnight.linkgraph.application.indexed.scopeKind
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
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

class ArchitectureGraphProjector(
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(),
    private val displayLayerResolver: ArchitectureDisplayLayerResolver = ArchitectureDisplayLayerResolver(),
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) {
    fun project(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest = requestArchitectureGraphRequest(),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ArchitectureGraphViewDocument {
        if (request.scope is IndexedGraphScope.Package) {
            return projectPackageGraph(index, request, cacheState, freshness)
        }
        val structureKinds = request.projectStructureKinds()
        val structureNodes = index.graph.nodes.filter { node ->
            node.kind in structureKinds && node.isVisibleProjectStructureNode(index)
        }
        val supportNodeIds = supportProjectStructureNodeIds(index)
        val relationBackedNodeIds = relationBackedProjectStructureNodeIds(index)
        val structureDisplayContexts = structureNodes.structureDisplayContexts()
        val fullGraph = projectStructureGraphDocument(
            index = index,
            nodes = structureNodes,
            request = request,
            displayContexts = structureDisplayContexts,
            supportNodeIds = supportNodeIds,
            relationBackedNodeIds = relationBackedNodeIds,
        )
        val visibleWindow = fullGraph.visibleWindow(
            policy = request.architectureStructureViewportPolicy(),
            seedNodeTypes = request.projectSeedNodeTypes(),
            nodePriority = ::architectureNodePriority,
            edgePriority = ::architectureEdgePriority,
        )
        val visibleGraph = visibleWindow.graph.withMissingArchitecturePresentationMetadata()
        val anchorNodeId = selectArchitectureAnchorNodeId(visibleGraph)
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        return ArchitectureGraphViewDocument(
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
                relationshipNodeCount = structureNodes.size,
                inventoryOnlyNodeCount = 0,
                unconnectedPackageCount = 0,
                unconnectedComponentCount = 0,
                unconnectedServiceBoundaryCount = 0,
                unconnectedResourceCount = 0,
                externalDependencyGroupCount = index.graph.nodes.count { it.kind == ArchitectureNodeKind.LIBRARY },
                jdkGroupCount = index.graph.nodes.count { it.kind == ArchitectureNodeKind.JDK },
                truncated = index.graph.truncated || visibleWindow.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                indexed = request.toSummary(
                    index = index,
                    visibleGraph = visibleGraph,
                    fullGraph = fullGraph,
                    anchorNodeId = anchorNodeId,
                    scopedNodeCount = fullGraph.nodes.size,
                    candidateNodeCount = structureNodes.size,
                    candidateEdgeCount = fullGraph.edges.size,
                    hiddenNodeCount = hiddenNodeCount,
                    hiddenEdgeCount = hiddenEdgeCount,
                    truncated = index.graph.truncated || visibleWindow.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                    cacheState = cacheState,
                    freshness = freshness,
                ),
                projectStructureRelationGroups = fullGraph.projectStructureRelationGroups(visibleGraph),
            ),
            projectionIndex = readonlyProjectionIndex(visibleGraph),
            presentation = architecturePresentation(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = anchorNodeId,
            ),
        )
    }

    private fun projectPackageGraph(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
        cacheState: String,
        freshness: IndexedGraphFreshness,
    ): ArchitectureGraphViewDocument {
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
        val packageDisplayContexts = relationshipNodes.structureDisplayContexts()
        val packageGraph = graphDocument(
            index = index,
            nodes = relationshipNodes,
            includeClassEdges = true,
            viewMode = AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            request = request,
            displayContexts = packageDisplayContexts,
        )
        val fullGraph = packageGraph.copy(
            edges = packageGraph.edges.filter { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" },
        )
        val visibleWindow = fullGraph.visibleWindow(
            policy = request.architectureViewportPolicy(),
            seedNodeTypes = setOf(NodeType.PACKAGE, NodeType.RESOURCE, NodeType.LIBRARY),
            nodePriority = ::architectureNodePriority,
            edgePriority = ::architectureEdgePriority,
        )
        val visibleGraph = visibleWindow.graph.withMissingArchitecturePresentationMetadata()
        val anchorNodeId = selectArchitectureAnchorNodeId(visibleGraph)
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        return ArchitectureGraphViewDocument(
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
            projectionIndex = readonlyProjectionIndex(visibleGraph),
            presentation = architecturePresentation(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = anchorNodeId,
            ),
        )
    }

    internal fun graphDocument(
        index: ArchitectureGraphIndex,
        nodes: List<ArchitectureNode>,
        includeClassEdges: Boolean,
        viewMode: AnalysisDisplayMode,
        request: IndexedGraphRequest,
        displayContexts: Map<String, StructureDisplayContext> = nodes.structureDisplayContexts(),
        supportNodeIds: Set<String> = emptySet(),
        relationBackedNodeIds: Set<String> = emptySet(),
    ): GraphDocument {
        val nodeIds = nodes.mapTo(linkedSetOf(), ArchitectureNode::id)
        val graphNodes = nodes.map { node ->
            node.toGraphNode(
                index = index,
                viewMode = viewMode,
                request = request,
                displayContext = displayContexts[node.id],
                supportNodeIds = supportNodeIds,
                relationBackedNodeIds = relationBackedNodeIds,
            )
        }
        val graphEdges = index.graph.edges
            .filter { edge ->
                edge.fromNodeId in nodeIds &&
                    edge.toNodeId in nodeIds &&
                    if (includeClassEdges) {
                        true
                    } else {
                        edge.metadata["architecture.aggregate.level"] == "OVERVIEW" ||
                            edge.kind in setOf(
                                JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                                JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                                JvmRelationKind.SPI_PROVIDES,
                            )
                    }
            }
            .map { edge ->
                GraphEdge(
                    id = edge.id,
                    type = edge.kind.toEdgeType(),
                    fromNodeId = edge.fromNodeId,
                    toNodeId = edge.toNodeId,
                    label = edgeLabel(edge.kind),
                    certainty = edge.confidence.toCertainty(),
                    bindingStatus = BindingStatus.BOUND,
                    metadata = edge.metadata + mapOf(
                        "linkGraph.view.mode" to viewMode.name,
                        "jvm.relation.kind" to edge.kind.name,
                        "jvm.relation.confidence" to edge.confidence.name,
                        "jvm.relation.source" to (edge.metadata["jvm.relation.source"] ?: "UNKNOWN"),
                        "jvm.relation.count" to edge.count.toString(),
                        "architecture.sourceRelationIds" to edge.sourceRelationIds.joinToString(","),
                    ) + edge.displayRelationMetadata() + edge.indexedEdgeMetadata(index),
                )
            }
        return GraphDocument(
            nodes = graphNodes.sortedBy(GraphNode::id),
            edges = graphEdges.sortedBy(GraphEdge::id),
        )
    }

    private fun projectStructureGraphDocument(
        index: ArchitectureGraphIndex,
        nodes: List<ArchitectureNode>,
        request: IndexedGraphRequest,
        displayContexts: Map<String, StructureDisplayContext>,
        supportNodeIds: Set<String>,
        relationBackedNodeIds: Set<String>,
    ): GraphDocument {
        val graph = graphDocument(
            index = index,
            nodes = nodes,
            includeClassEdges = false,
            viewMode = AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            request = request,
            displayContexts = displayContexts,
            supportNodeIds = supportNodeIds,
            relationBackedNodeIds = relationBackedNodeIds,
        )
        return graph.copy(
            edges = graph.edges
                .filter { edge ->
                    edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                        edge.metadata["jvm.relation.kind"] !in hiddenProjectStructureRelationKinds
                }
                .sortedBy(GraphEdge::id),
        )
    }

    private fun GraphDocument.projectStructureRelationGroups(visibleGraph: GraphDocument): List<ProjectStructureRelationGroup> {
        val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
        return edges
            .filter { edge -> edge.metadata["architecture.aggregate.level"] == "OVERVIEW" }
            .groupBy { edge ->
                listOf(
                    edge.fromNodeId,
                    edge.toNodeId,
                    edge.metadata["architecture.displayRelationKind"] ?: edge.metadata["jvm.relation.kind"] ?: edge.type.name,
                ).joinToString("|")
            }
            .values
            .map { groupEdges ->
                val sortedEdges = groupEdges.sortedBy(GraphEdge::id)
                val first = sortedEdges.first()
                val sourceRelationIds = sortedEdges
                    .flatMap { edge ->
                        listOf(
                            edge.metadata["architecture.sourceRelationIds"],
                            edge.metadata["indexed.sourceRelationIds"],
                        )
                    }
                    .flatMap { raw -> raw.orEmpty().split(',') }
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                val count = sortedEdges.sumOf { edge -> edge.metadata["jvm.relation.count"]?.toIntOrNull() ?: 1 }
                val defaultVisible = sortedEdges.any { edge -> edge.id in visibleEdgeIds }
                ProjectStructureRelationGroup(
                    id = "project-structure:${first.fromNodeId}->${first.toNodeId}:${first.metadata["architecture.displayRelationKind"] ?: first.type.name}",
                    fromNodeId = first.fromNodeId,
                    toNodeId = first.toNodeId,
                    displayRelationKind = first.metadata["architecture.displayRelationKind"] ?: first.metadata["jvm.relation.kind"] ?: first.type.name,
                    displayRelation = first.metadata["architecture.displayRelation"] ?: first.label ?: first.type.name,
                    relationKinds = sortedEdges.map { edge -> edge.metadata["jvm.relation.kind"] ?: edge.type.name }.distinct(),
                    count = count,
                    confidence = sortedEdges.map { edge -> edge.metadata["jvm.relation.confidence"] ?: edge.certainty.name }.distinct().joinToString(","),
                    sourceRelationIds = sourceRelationIds,
                    sampleEvidenceRefs = sourceRelationIds.take(5),
                    defaultVisible = defaultVisible,
                    hiddenReason = if (defaultVisible) null else "OUTSIDE_DEFAULT_PROJECT_STRUCTURE_WINDOW",
                )
            }
            .sortedWith(
                compareByDescending<ProjectStructureRelationGroup> { group -> group.defaultVisible }
                    .thenByDescending { group -> group.count }
                    .thenBy { group -> group.displayRelationKind }
                    .thenBy { group -> group.id },
            )
    }

    internal fun readonlyProjectionIndex(graph: GraphDocument): GraphProjectionIndex =
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
                    canonicalEdgeIds = listOf(edge.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
        )

    private fun IndexedGraphRequest.architectureViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: viewportPolicy.maxVisibleNodes,
            maxVisibleEdges = viewport.maxVisibleEdges ?: viewportPolicy.maxVisibleEdges,
            enableOverflowSummary = viewportPolicy.enableOverflowSummary,
        )

    private fun IndexedGraphRequest.architectureStructureViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: DEFAULT_STRUCTURE_VISIBLE_NODES,
            maxVisibleEdges = viewport.maxVisibleEdges ?: DEFAULT_STRUCTURE_VISIBLE_EDGES,
            enableOverflowSummary = viewportPolicy.enableOverflowSummary,
        )

    private fun ArchitectureNode.toGraphNode(
        index: ArchitectureGraphIndex,
        viewMode: AnalysisDisplayMode,
        request: IndexedGraphRequest,
        displayContext: StructureDisplayContext?,
        supportNodeIds: Set<String>,
        relationBackedNodeIds: Set<String>,
    ): GraphNode {
        val location = source?.startLine?.let { line -> "${source.displayPath}:$line" } ?: source?.displayPath
        val displayLayer = displayLayerResolver.resolve(this, index)
        return GraphNode(
            id = id,
            type = kind.toNodeType(),
            title = title,
            location = location,
            signature = qualifiedName.takeIf { kind.isTypeLike() },
            doc = metadata["jvm.class.docComment"] ?: docText(),
            sourceKind = resourceKind?.name,
            bindingStatus = BindingStatus.BOUND,
            certainty = Certainty.PROVEN,
            metadata = metadata + buildMap {
                put("linkGraph.view.mode", viewMode.name)
                put("architecture.node.kind", kind.name)
                put("architecture.qualifiedName", qualifiedName)
                moduleName?.let { put("architecture.module", it) }
                packageName?.let { put("architecture.package", it) }
                classKind?.let { put("jvm.class.kind", it.name) }
                stereotype?.let { put("jvm.stereotype", it.name) }
                resourceKind?.let {
                    put("jvm.resource.kind", it.name)
                    if (it == com.charmnight.linkgraph.jvm.index.JvmResourceKind.MQ_TOPIC) {
                        put("resource.displayKind", "MQ_TOPIC")
                    }
                }
                if (memberClassIds.isNotEmpty()) {
                    put("architecture.memberClassIds", memberClassIds.joinToString(","))
                    put("architecture.package.classCount", memberClassIds.size.toString())
                    put("architecture.drillDownClassScope", id)
                }
                if (memberResourceIds.isNotEmpty()) {
                    put("architecture.memberResourceIds", memberResourceIds.joinToString(","))
                }
                source?.displayPath?.let { put("source.filePath", it) }
                source?.virtualFileUrl?.let { put("source.virtualFileUrl", it) }
                source?.startLine?.let { put("source.startLine", it.toString()) }
                source?.endLine?.let { put("source.endLine", it.toString()) }
                index.symbolIndex.symbolsById[id]?.origin?.let { origin -> put("source.origin", origin.name) }
                put("source.decompiled", (source?.decompiled ?: false).toString())
                putAll(structureDisplayMetadata(index, displayLayer, displayContext, id in supportNodeIds, id in relationBackedNodeIds))
                putAll(architectureSourceSampleMetadata(index))
                putAll(indexedNodeMetadata(index, request.scopeKind()))
                putAll(displayLayer.presentationMetadata())
            },
        )
    }

    private fun ArchitectureNode.structureDisplayMetadata(
        index: ArchitectureGraphIndex,
        displayLayer: ArchitectureDisplayLayer,
        displayContext: StructureDisplayContext?,
        supportNode: Boolean,
        relationBackedNode: Boolean,
    ): Map<String, String> {
        val tooBroad = isBroadProjectStructureAggregate(index)
        val displayName = when (kind) {
            ArchitectureNodeKind.RESOURCE -> title.ifBlank { qualifiedName }
            else -> qualifiedName.ifBlank { displayContext?.displayName ?: title }
        }
        return buildMap {
            put("architecture.displayName", displayName)
            put("architecture.displaySubtitle", structureSubtitle(displayName, displayLayer))
            displayContext?.readableBaseName?.let { put("architecture.displayBaseName", it) }
            put("architecture.displayLayer", displayLayer.name)
            put("architecture.displayRole", displayLayer.role)
            put("architecture.structureReadable", (!tooBroad).toString())
            put("architecture.structureTooBroad", tooBroad.toString())
            put("architecture.structureSupport", supportNode.toString())
            put("architecture.structureRelationBacked", relationBackedNode.toString())
            put("architecture.structureRank", structureRank(index, displayLayer, tooBroad, supportNode, relationBackedNode).toString())
            put("architecture.structureAggregationKind", metadata["architecture.boundary.kind"] ?: kind.name)
        }
    }

    private fun ArchitectureNode.readableStructureName(): String {
        if (kind == ArchitectureNodeKind.RESOURCE) {
            return title
        }
        return title.ifBlank { qualifiedName.substringAfterLast('.') }
    }

    private fun ArchitectureNode.structureSubtitle(
        displayName: String,
        displayLayer: ArchitectureDisplayLayer,
    ): String =
        when (kind) {
            ArchitectureNodeKind.RESOURCE -> "资源 · ${memberResourceIds.size.coerceAtLeast(1)} 项"
            ArchitectureNodeKind.SERVICE -> "${displayLayer.label} · 服务边界"
            ArchitectureNodeKind.COMPONENT -> "${displayLayer.label} · 组件"
            ArchitectureNodeKind.LIBRARY -> "外部依赖 · ${memberClassIds.size} 类型"
            ArchitectureNodeKind.JDK -> "JDK · ${memberClassIds.size} 类型"
            else -> displayLayer.label
        }

    private fun ArchitectureNode.structureRank(
        index: ArchitectureGraphIndex,
        displayLayer: ArchitectureDisplayLayer,
        tooBroad: Boolean,
        supportNode: Boolean,
        relationBackedNode: Boolean,
    ): Int {
        if (tooBroad) {
            return 90_000
        }
        val name = readableStructureName().lowercase()
        val nameRank = when (name) {
            "api", "controller", "web" -> 0
            "service", "application", "app" -> 1
            "domain", "model" -> 2
            "repository", "dao", "mapper", "data" -> 3
            "config", "infra", "infrastructure" -> 4
            "resource", "resources" -> 5
            else -> 40
        }
        val supportPenalty = if (supportNode) 5_000 else 0
        val orphanPenalty = if (relationBackedNode) 0 else 2_000
        val roleRank = memberRoleRank(index)
        val sizeBoost = (100 - memberClassIds.size.coerceAtMost(100)).coerceAtLeast(0)
        return supportPenalty + orphanPenalty + displayLayer.order * 100 + roleRank * 10 + nameRank + sizeBoost
    }

    private fun ArchitectureNode.memberRoleRank(index: ArchitectureGraphIndex): Int {
        val memberClasses = memberClassIds.mapNotNull { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
        if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.CONTROLLER }) {
            return 0
        }
        if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.SERVICE }) {
            return 1
        }
        if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.REPOSITORY }) {
            return 2
        }
        if (memberClasses.any { cls -> cls.stereotype == JvmStereotype.CONFIGURATION }) {
            return 3
        }
        return 4
    }

    private fun supportProjectStructureNodeIds(index: ArchitectureGraphIndex): Set<String> =
        index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in setOf(ArchitectureNodeKind.COMPONENT, ArchitectureNodeKind.SERVICE) }
            .filter { node -> node.isSupportProjectStructureNode(index) }
            .map(ArchitectureNode::id)
            .toSet()

    private fun relationBackedProjectStructureNodeIds(index: ArchitectureGraphIndex): Set<String> =
        index.graph.edges
            .asSequence()
            .filter { edge ->
                edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                    edge.metadata["jvm.relation.kind"] !in hiddenProjectStructureRelationKinds
            }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .toSet()

    private fun ArchitectureNode.isSupportProjectStructureNode(index: ArchitectureGraphIndex): Boolean {
        if (kind !in setOf(ArchitectureNodeKind.COMPONENT, ArchitectureNodeKind.SERVICE)) {
            return false
        }
        val packageSegments = qualifiedName.split('.').filter(String::isNotBlank).map(String::lowercase)
        if (packageSegments.any { segment -> segment in supportPackageSegments }) {
            return true
        }
        val memberClasses = memberClassIds.mapNotNull { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
        if (memberClasses.isEmpty()) {
            return false
        }
        return memberClasses.all { cls ->
            cls.testSource ||
                cls.source?.displayPath?.hasSupportSourcePath() == true ||
                cls.packageName.split('.').filter(String::isNotBlank).map(String::lowercase).any { segment ->
                    segment in supportPackageSegments
                }
        }
    }

    private fun String.hasSupportSourcePath(): Boolean {
        val segments = replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
            .map(String::lowercase)
        return segments.any { segment -> segment in supportSourcePathSegments }
    }

    private fun List<ArchitectureNode>.structureDisplayContexts(): Map<String, StructureDisplayContext> {
        val readableBaseNames = associate { node -> node.id to node.readableStructureBaseName(this) }
        val duplicateBaseNames = readableBaseNames.values
            .filter(String::isNotBlank)
            .groupingBy { name -> name }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        return associate { node ->
            val baseName = readableBaseNames.getValue(node.id)
            val displayName = if (baseName in duplicateBaseNames) {
                node.shortestUniqueStructureName(this)
            } else {
                baseName
            }
            node.id to StructureDisplayContext(
                displayName = displayName.ifBlank { node.title.ifBlank { node.qualifiedName } },
                readableBaseName = baseName.ifBlank { null },
            )
        }
    }

    private fun ArchitectureNode.readableStructureBaseName(allNodes: List<ArchitectureNode>): String {
        if (kind == ArchitectureNodeKind.RESOURCE) {
            return title
        }
        val parts = qualifiedName.split('.').filter(String::isNotBlank)
        if (parts.isEmpty()) {
            return title.ifBlank { qualifiedName }
        }
        val projectNames = allNodes
            .asSequence()
            .map { node -> node.qualifiedName.split('.').filter(String::isNotBlank) }
            .filter(List<String>::isNotEmpty)
            .toList()
        val rootSize = commonRootSize(projectNames)
        val rootTrimmedParts = parts.drop(rootSize).takeIf(List<String>::isNotEmpty) ?: parts
        return projectNamespaceTrimmedParts(rootTrimmedParts)
            .joinToString(".")
            .ifBlank { title.ifBlank { qualifiedName } }
    }

    private fun ArchitectureNode.projectNamespaceTrimmedParts(parts: List<String>): List<String> {
        val moduleSegment = moduleName
            ?.substringAfterLast(':')
            ?.substringBeforeLast('.')
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
        if (moduleSegment != null) {
            val moduleIndex = parts.indexOfFirst { part -> part.lowercase() == moduleSegment }
            if (moduleIndex >= 0 && moduleIndex < parts.lastIndex) {
                return parts.drop(moduleIndex + 1)
            }
        }
        val organizationTrimmedParts = parts.dropWhile { part -> part.lowercase() in organizationPrefixSegments }
        return organizationTrimmedParts.takeIf(List<String>::isNotEmpty) ?: parts
    }

    private fun ArchitectureNode.shortestUniqueStructureName(allNodes: List<ArchitectureNode>): String {
        if (kind == ArchitectureNodeKind.RESOURCE) {
            return title
        }
        val parts = qualifiedName.split('.').filter(String::isNotBlank)
        if (parts.size <= 2) {
            return qualifiedName.ifBlank { title }
        }
        for (suffixSize in 2..parts.size) {
            val suffix = parts.takeLast(suffixSize).joinToString(".")
            val collides = allNodes.any { other ->
                other.id != id &&
                    other.kind != ArchitectureNodeKind.RESOURCE &&
                    other.qualifiedName
                        .split('.')
                        .filter(String::isNotBlank)
                        .takeLast(suffixSize)
                        .joinToString(".") == suffix
            }
            if (!collides) {
                return suffix
            }
        }
        return qualifiedName.ifBlank { title }
    }

    private fun commonRootSize(names: List<List<String>>): Int {
        if (names.isEmpty()) {
            return 0
        }
        val first = names.first()
        var rootSize = 0
        for (index in first.indices) {
            val part = first[index]
            if (names.all { name -> name.getOrNull(index) == part }) {
                rootSize += 1
            } else {
                break
            }
        }
        return rootSize
    }

    private fun ArchitectureNode.isBroadProjectStructureAggregate(index: ArchitectureGraphIndex): Boolean {
        if (kind !in setOf(ArchitectureNodeKind.COMPONENT, ArchitectureNodeKind.SERVICE)) {
            return false
        }
        val parts = qualifiedName.split('.').filter(String::isNotBlank)
        if (parts.size <= 1) {
            return true
        }
        if (readableStructureName().isBlank()) {
            return true
        }
        val projectClassCount = index.symbolIndex.classesByQualifiedName.values.count { cls ->
            !cls.external && !cls.library && !cls.jdk && !cls.testSource
        }.coerceAtLeast(1)
        if (memberClassIds.size > projectClassCount * BROAD_STRUCTURE_NODE_RATIO) {
            return true
        }
        return false
    }

    private fun architecturePresentation(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
    ): GraphViewPresentation {
        val targetNode = anchorNodeId?.let { nodeId -> fullGraph.nodes.firstOrNull { it.id == nodeId } }
            ?: visibleGraph.nodes.firstOrNull()
        val targetLayer = targetNode?.let(displayLayerResolver::resolve)
        return GraphViewPresentation(
            target = GraphPresentationTarget(
                nodeId = anchorNodeId,
                title = targetNode?.title.orEmpty(),
                subtitle = targetLayer?.label.orEmpty(),
                location = targetNode?.location,
            ),
            lanes = ArchitectureDisplayLayer.entries.map { layer ->
                GraphPresentationLane(
                    id = layer.laneId,
                    label = layer.label,
                    axis = GraphPresentationLaneAxis.ROW,
                    order = layer.order,
                    role = layer.role,
                )
            },
            hiddenBuckets = hiddenBucketProjector.project(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                bucketForNode = { node -> node.metadata["presentation.laneId"] ?: displayLayerResolver.resolve(node).laneId },
                labelForBucket = ::architectureBucketLabel,
            ),
            controls = GraphPresentationControls(
                primaryScope = "组件",
                availableScopes = listOf("组件", "包", "类"),
            ),
        )
    }

    private fun GraphDocument.withMissingArchitecturePresentationMetadata(): GraphDocument =
        copy(
            nodes = nodes.map { node ->
                if (node.metadata["presentation.role"] != null) {
                    node
                } else {
                    node.copy(metadata = node.metadata + displayLayerResolver.resolve(node).presentationMetadata())
                }
            },
        )

    private fun ArchitectureDisplayLayer.presentationMetadata(): Map<String, String> =
        mapOf(
            "presentation.role" to role,
            "presentation.laneId" to laneId,
            "presentation.priority" to order.toString(),
            "presentation.compact" to "true",
        )

    private fun architectureBucketLabel(bucket: String): String =
        ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == bucket }?.label ?: bucket

    private fun ArchitectureNode.architectureSourceSampleMetadata(
        index: ArchitectureGraphIndex,
    ): Map<String, String> {
        val samples = architectureSourceSamples(index)
        if (samples.isEmpty()) {
            return emptyMap()
        }
        return buildMap {
            put("architecture.sourceSample.count", samples.size.toString())
            val primarySample = samples.first()
            putAll(
                SourceNavigationAnchors.metadata(
                    nodeId = primarySample.nodeId,
                    filePath = primarySample.source.displayPath,
                    virtualFileUrl = primarySample.source.virtualFileUrl,
                    startLine = primarySample.source.startLine,
                    endLine = primarySample.source.endLine,
                    reason = primarySample.reason,
                ),
            )
            samples.forEachIndexed { sampleIndex, sample ->
                val prefix = "architecture.sourceSample.$sampleIndex"
                put("$prefix.nodeId", sample.nodeId)
                put("$prefix.filePath", sample.source.displayPath)
                sample.source.virtualFileUrl?.let { put("$prefix.virtualFileUrl", it) }
                sample.source.startLine?.let { put("$prefix.startLine", it.toString()) }
                sample.source.endLine?.let { put("$prefix.endLine", it.toString()) }
                put("$prefix.decompiled", sample.source.decompiled.toString())
                put("$prefix.reason", sample.reason)
            }
        }
    }

    private fun ArchitectureNode.architectureSourceSamples(
        index: ArchitectureGraphIndex,
    ): List<ArchitectureSourceSample> {
        val samplesByNodeId = linkedMapOf<String, ArchitectureSourceSample>()
        source?.let { nodeSource ->
            samplesByNodeId[id] = ArchitectureSourceSample(
                nodeId = id,
                source = nodeSource,
                reason = "architecture-node-source",
            )
        }
        memberClassIds
            .asSequence()
            .mapNotNull { memberNodeId ->
                val symbol = index.findSymbol(memberNodeId) as? JvmClassSymbol
                symbol?.source?.let { memberSource ->
                    ArchitectureSourceSample(
                        nodeId = symbol.id,
                        source = memberSource,
                        reason = "architecture-member-class:$id",
                    )
                }
            }
            .forEach { sample -> samplesByNodeId.putIfAbsent(sample.nodeId, sample) }
        memberResourceIds
            .asSequence()
            .mapNotNull { memberNodeId ->
                val symbol = index.findSymbol(memberNodeId) as? JvmResourceSymbol
                symbol?.source?.let { memberSource ->
                    ArchitectureSourceSample(
                        nodeId = symbol.id,
                        source = memberSource,
                        reason = "architecture-member-resource:$id",
                    )
                }
            }
            .forEach { sample -> samplesByNodeId.putIfAbsent(sample.nodeId, sample) }
        return samplesByNodeId.values
            .sortedWith(compareBy({ it.source.displayPath }, { it.source.startLine ?: Int.MAX_VALUE }, { it.nodeId }))
            .take(MAX_ARCHITECTURE_SOURCE_SAMPLES)
    }

    private fun ArchitectureNode.docText(): String? =
        when (kind) {
            ArchitectureNodeKind.SERVICE -> "Service scope with ${memberClassIds.size} classes"
            ArchitectureNodeKind.COMPONENT -> "Component group with ${memberClassIds.size} classes"
            ArchitectureNodeKind.LAYER -> "Layer aggregate with ${memberClassIds.size} classes"
            ArchitectureNodeKind.PACKAGE -> "Package with ${memberClassIds.size} classes"
            ArchitectureNodeKind.LIBRARY -> "External dependency group with ${memberClassIds.size} classes"
            ArchitectureNodeKind.JDK -> "JDK group with ${memberClassIds.size} classes"
            else -> null
        }

    private fun ArchitectureNodeKind.toNodeType(): NodeType =
        when (this) {
            ArchitectureNodeKind.MODULE -> NodeType.MODULE
            ArchitectureNodeKind.PACKAGE -> NodeType.PACKAGE
            ArchitectureNodeKind.COMPONENT -> NodeType.COMPONENT
            ArchitectureNodeKind.CLASS -> NodeType.CLASS
            ArchitectureNodeKind.INTERFACE -> NodeType.INTERFACE
            ArchitectureNodeKind.ENUM -> NodeType.ENUM
            ArchitectureNodeKind.ANNOTATION -> NodeType.ANNOTATION
            ArchitectureNodeKind.RECORD -> NodeType.RECORD
            ArchitectureNodeKind.OBJECT -> NodeType.OBJECT
            ArchitectureNodeKind.SERVICE -> NodeType.SERVICE
            ArchitectureNodeKind.RESOURCE -> NodeType.RESOURCE
            ArchitectureNodeKind.LAYER -> NodeType.LAYER
            ArchitectureNodeKind.LIBRARY,
            ArchitectureNodeKind.JDK,
            -> NodeType.LIBRARY
        }

    private fun ArchitectureNodeKind.isTypeLike(): Boolean =
        this in setOf(
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
        )

    private fun architectureNodePriority(node: GraphNode): Int =
        node.metadata["architecture.structureRank"]?.toIntOrNull()
            ?: when (node.type) {
            NodeType.MODULE -> 0
            NodeType.LAYER -> 1
            NodeType.SERVICE -> 2
            NodeType.COMPONENT -> 3
            NodeType.PACKAGE -> 4
            NodeType.RESOURCE -> 4
            NodeType.LIBRARY -> 5
            else -> 6
        }

    private fun architectureEdgePriority(edge: GraphEdge): Int =
        when {
            edge.metadata["architecture.graph.kind"] == "STRUCTURE" -> 0
            else -> when (edge.metadata["architecture.aggregate"]) {
                "LAYER" -> 1
                "SERVICE" -> 2
                "COMPONENT" -> 3
                "RESOURCE" -> 4
                "PACKAGE" -> 5
                else -> when (edge.metadata["jvm.relation.kind"]) {
                    JvmRelationKind.MODULE_CONTAINS_PACKAGE.name -> 6
                    JvmRelationKind.SPI_PROVIDES.name -> 7
                    else -> 6
                }
            }
        }

    private fun ArchitectureEdge.displayRelationMetadata(): Map<String, String> =
        when (kind) {
            JvmRelationKind.CALLS,
            JvmRelationKind.INJECTS,
            JvmRelationKind.FEIGN_ROUTES_TO,
            JvmRelationKind.SPRING_ROUTES_TO,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.MQ_CONSUMES,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            -> mapOf(
                "architecture.displayRelationKind" to "RUNTIME_CALL",
                "architecture.displayRelation" to "运行时调用",
            )
            JvmRelationKind.USES_TYPE,
            JvmRelationKind.EXTENDS,
            JvmRelationKind.IMPLEMENTS,
            JvmRelationKind.ANNOTATED_BY,
            -> mapOf(
                "architecture.displayRelationKind" to "TYPE_DEPENDENCY",
                "architecture.displayRelation" to "类型依赖",
            )
            JvmRelationKind.RESOURCE_BINDS,
            -> mapOf(
                "architecture.displayRelationKind" to "RESOURCE_BINDING",
                "architecture.displayRelation" to "资源绑定",
            )
            JvmRelationKind.TESTS,
            -> mapOf(
                "architecture.displayRelationKind" to "TEST_RELATION",
                "architecture.displayRelation" to "测试关系",
            )
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.DUBBO_PROVIDES,
            -> mapOf(
                "architecture.displayRelationKind" to "RUNTIME_DISCOVERY",
                "architecture.displayRelation" to "运行时发现",
            )
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.DUBBO_REFERENCES,
            -> mapOf(
                "architecture.displayRelationKind" to "INTEGRATION_BINDING",
                "architecture.displayRelation" to "集成绑定",
            )
            JvmRelationKind.MODULE_CONTAINS_PACKAGE,
            JvmRelationKind.PACKAGE_CONTAINS_CLASS,
            -> mapOf(
                "architecture.displayRelationKind" to "STRUCTURE_CONTAINS",
                "architecture.displayRelation" to "结构包含",
            )
        }

    private fun selectArchitectureAnchorNodeId(graph: GraphDocument): String? {
        val preferredRoles = listOf("API", "ENTRY", "SERVICE", "DATA", "CONFIG", "RESOURCE")
        preferredRoles.forEach { role ->
            graph.nodes.firstOrNull { node ->
                node.metadata["indexed.nodeRole"] == role &&
                    node.type != NodeType.MODULE &&
                    !node.isBroadArchitectureAggregate(graph.nodes) &&
                    node.hasArchitectureSourceSamples()
            }?.let { return it.id }
        }
        val sourceBackedAggregateTypes = listOf(
            NodeType.SERVICE,
            NodeType.LAYER,
            NodeType.RESOURCE,
            NodeType.COMPONENT,
            NodeType.LIBRARY,
        )
        sourceBackedAggregateTypes.forEach { nodeType ->
            graph.nodes.firstOrNull { node ->
                node.type == nodeType && !node.isBroadArchitectureAggregate(graph.nodes) && node.hasArchitectureSourceSamples()
            }?.let { return it.id }
        }
        preferredRoles.forEach { role ->
            graph.nodes.firstOrNull { node ->
                node.metadata["indexed.nodeRole"] == role &&
                    node.type != NodeType.MODULE &&
                    !node.isBroadArchitectureAggregate(graph.nodes)
            }?.let { return it.id }
        }
        return graph.nodes.firstOrNull { it.type == NodeType.MODULE }?.id ?: graph.nodes.firstOrNull()?.id
    }

    private fun GraphNode.hasArchitectureSourceSamples(): Boolean =
        metadata["architecture.sourceSample.count"]?.toIntOrNull()?.let { count -> count > 0 } == true

    private fun GraphNode.isBroadArchitectureAggregate(allNodes: List<GraphNode>): Boolean {
        val nodeKind = metadata["architecture.node.kind"] ?: type.name
        val role = metadata["indexed.nodeRole"]
        val qualifiedName = metadata["architecture.qualifiedName"].orEmpty()
        val boundaryKind = metadata["architecture.boundary.kind"]
        if (nodeKind == ArchitectureNodeKind.MODULE.name) {
            return true
        }
        if (nodeKind == ArchitectureNodeKind.COMPONENT.name && role == "UNKNOWN") {
            return boundaryKind != "PROJECT_SERVICE_BOUNDARY" && qualifiedName.isBroadComponentNamespace(allNodes)
        }
        return false
    }

    private fun String.isBroadComponentNamespace(allNodes: List<GraphNode>): Boolean {
        if (isBlank()) {
            return false
        }
        return allNodes.any { other ->
            other.metadata["architecture.qualifiedName"].orEmpty().startsWith("$this.") &&
                other.metadata["architecture.node.kind"] in setOf(
                    ArchitectureNodeKind.SERVICE.name,
                    ArchitectureNodeKind.COMPONENT.name,
                    ArchitectureNodeKind.PACKAGE.name,
                    ArchitectureNodeKind.LAYER.name,
                )
        } || allNodes
            .asSequence()
            .filter { other ->
            other.metadata["architecture.qualifiedName"] != this &&
                    other.metadata["architecture.node.kind"] in setOf(
                        ArchitectureNodeKind.SERVICE.name,
                        ArchitectureNodeKind.COMPONENT.name,
                    )
            }
            .map { other -> other.metadata["architecture.qualifiedName"].orEmpty() }
            .filter { otherQualifiedName -> otherQualifiedName.startsWith("$this.") }
            .count() >= 2
    }

    private fun ArchitectureNode.isVisibleProjectStructureNode(index: ArchitectureGraphIndex): Boolean {
        if (kind != ArchitectureNodeKind.RESOURCE) {
            return true
        }
        val paths = resourcePaths(index)
        return paths.isEmpty() || paths.any { path -> !path.hasExcludedResourcePathSegment() }
    }

    private fun ArchitectureNode.resourcePaths(index: ArchitectureGraphIndex): List<String> =
        buildList {
            metadata["resource.path"]?.let(::add)
            memberResourceIds.mapNotNullTo(this) { resourceId ->
                (index.symbolIndex.findSymbol(resourceId) as? JvmResourceSymbol)?.path
            }
        }

    private fun String.hasExcludedResourcePathSegment(): Boolean {
        val segments = replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
        return segments.withIndex().any { (index, segment) ->
            segment in alwaysExcludedResourcePathSegments ||
                segment in generatedResourcePathSegments && "src" !in segments.take(index)
        }
    }

    private fun IndexedGraphRequest.projectStructureKinds(): Set<ArchitectureNodeKind> =
        buildSet {
            add(ArchitectureNodeKind.SERVICE)
            add(ArchitectureNodeKind.COMPONENT)
            add(ArchitectureNodeKind.RESOURCE)
            if (includeExternalLibraries) {
                add(ArchitectureNodeKind.LIBRARY)
            }
            if (includeJdk) {
                add(ArchitectureNodeKind.JDK)
            }
        }

    private fun IndexedGraphRequest.projectSeedNodeTypes(): Set<NodeType> =
        buildSet {
            add(NodeType.SERVICE)
            add(NodeType.COMPONENT)
            add(NodeType.RESOURCE)
            if (includeExternalLibraries || includeJdk) {
                add(NodeType.LIBRARY)
            }
        }

    private data class ArchitectureSourceSample(
        val nodeId: String,
        val source: JvmSourceRef,
        val reason: String,
    )

    internal data class StructureDisplayContext(
        val displayName: String,
        val readableBaseName: String?,
    )

    private companion object {
        private const val MAX_ARCHITECTURE_SOURCE_SAMPLES = 8
        private const val DEFAULT_STRUCTURE_VISIBLE_NODES = 12
        private const val DEFAULT_STRUCTURE_VISIBLE_EDGES = 18
        private const val BROAD_STRUCTURE_NODE_RATIO = 0.55
        private val hiddenProjectStructureRelationKinds = setOf(
            JvmRelationKind.MODULE_CONTAINS_PACKAGE.name,
            JvmRelationKind.PACKAGE_CONTAINS_CLASS.name,
            JvmRelationKind.SPI_PROVIDES.name,
            JvmRelationKind.SERVICE_LOADER_LOADS.name,
        )
        private val alwaysExcludedResourcePathSegments = setOf(
            ".cache",
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".nuxt",
            ".parcel-cache",
            "build-idea-sandbox",
            "node_modules",
        )
        private val generatedResourcePathSegments = setOf(
            "build",
            "coverage",
            "dist",
            "out",
            "target",
            "temp",
            "tmp",
        )
        private val organizationPrefixSegments = setOf(
            "com",
            "org",
            "net",
            "io",
            "dev",
        )
        private val supportPackageSegments = setOf(
            "benchmark",
            "benchmarks",
            "demo",
            "docker",
            "example",
            "examples",
            "fixture",
            "fixtures",
            "mock",
            "mocks",
            "sample",
            "samples",
            "test",
            "testing",
            "tests",
        )
        private val supportSourcePathSegments = supportPackageSegments + setOf(
            "src/test",
            "src/integrationtest",
            "src/integration-test",
        )
    }
}

internal fun JvmRelationKind.toEdgeType(): EdgeType =
    when (this) {
        JvmRelationKind.MODULE_CONTAINS_PACKAGE,
        JvmRelationKind.PACKAGE_CONTAINS_CLASS,
        -> EdgeType.CONTAINS_FLOW
        JvmRelationKind.EXTENDS -> EdgeType.EXTENDS
        JvmRelationKind.IMPLEMENTS -> EdgeType.IMPLEMENTS
        JvmRelationKind.USES_TYPE -> EdgeType.USES_TYPE
        JvmRelationKind.INJECTS -> EdgeType.INJECT
        JvmRelationKind.CALLS -> EdgeType.CALL
        JvmRelationKind.TESTS -> EdgeType.TESTS
        JvmRelationKind.SPI_PROVIDES,
        JvmRelationKind.SERVICE_LOADER_LOADS,
        -> EdgeType.SPI_RESOLVES_TO
        JvmRelationKind.REFLECTS_TO -> EdgeType.REFLECTS_TO
        JvmRelationKind.USES_PROXY -> EdgeType.USES_PROXY
        JvmRelationKind.RESOURCE_BINDS -> EdgeType.BINDS_CONFIG
        JvmRelationKind.DUBBO_REFERENCES -> EdgeType.USES_PROXY
        JvmRelationKind.DUBBO_PROVIDES -> EdgeType.SPI_RESOLVES_TO
        JvmRelationKind.FEIGN_CLIENT_CALLS -> EdgeType.USES_PROXY
        JvmRelationKind.FEIGN_ROUTES_TO,
        JvmRelationKind.SPRING_ROUTES_TO,
        -> EdgeType.ROUTES_TO
        JvmRelationKind.MQ_PUBLISHES -> EdgeType.PUBLISHES_TO
        JvmRelationKind.MQ_CONSUMES -> EdgeType.CONSUMES_FROM
        JvmRelationKind.ANNOTATED_BY,
        JvmRelationKind.SPRING_EVENT_PUBLISHES,
        JvmRelationKind.SPRING_EVENT_LISTENS,
        -> EdgeType.USES_TYPE
    }

internal fun JvmRelationConfidence.toCertainty(): Certainty =
    when (this) {
        JvmRelationConfidence.PROVEN -> Certainty.PROVEN
        JvmRelationConfidence.RULE_INFERRED,
        JvmRelationConfidence.AMBIGUOUS,
        JvmRelationConfidence.RUNTIME_REQUIRED,
        -> Certainty.RULE_INFERRED
    }

internal fun edgeLabel(kind: JvmRelationKind): String =
    when (kind) {
        JvmRelationKind.MODULE_CONTAINS_PACKAGE -> "contains"
        JvmRelationKind.PACKAGE_CONTAINS_CLASS -> "contains"
        JvmRelationKind.EXTENDS -> "extends"
        JvmRelationKind.IMPLEMENTS -> "implements"
        JvmRelationKind.USES_TYPE -> "uses"
        JvmRelationKind.INJECTS -> "injects"
        JvmRelationKind.CALLS -> "calls"
        JvmRelationKind.TESTS -> "tests"
        JvmRelationKind.ANNOTATED_BY -> "annotated"
        JvmRelationKind.SPI_PROVIDES -> "SPI"
        JvmRelationKind.SERVICE_LOADER_LOADS -> "loads"
        JvmRelationKind.REFLECTS_TO -> "reflects"
        JvmRelationKind.USES_PROXY -> "proxy"
        JvmRelationKind.SPRING_EVENT_PUBLISHES -> "publishes"
        JvmRelationKind.SPRING_EVENT_LISTENS -> "listens"
        JvmRelationKind.DUBBO_PROVIDES -> "dubbo provides"
        JvmRelationKind.DUBBO_REFERENCES -> "dubbo references"
        JvmRelationKind.FEIGN_CLIENT_CALLS -> "feign client"
        JvmRelationKind.FEIGN_ROUTES_TO,
        JvmRelationKind.SPRING_ROUTES_TO,
        -> "routes"
        JvmRelationKind.MQ_PUBLISHES -> "publishes"
        JvmRelationKind.MQ_CONSUMES -> "consumes"
        JvmRelationKind.RESOURCE_BINDS -> "binds"
    }
