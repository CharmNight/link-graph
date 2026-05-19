package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

class ArchitectureGraphProjector(
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(),
) {
    fun project(index: ArchitectureGraphIndex): ArchitectureGraphViewDocument {
        val aggregateKinds = setOf(
            ArchitectureNodeKind.MODULE,
            ArchitectureNodeKind.PACKAGE,
            ArchitectureNodeKind.SERVICE,
            ArchitectureNodeKind.RESOURCE,
            ArchitectureNodeKind.LAYER,
        )
        val fullGraph = graphDocument(
            index = index,
            nodes = index.graph.nodes.filter { node -> node.kind in aggregateKinds },
            includeClassEdges = false,
            viewMode = AnalysisDisplayMode.ARCHITECTURE_GRAPH,
        )
        val visibleWindow = fullGraph.visibleWindow(
            policy = viewportPolicy,
            seedNodeTypes = setOf(NodeType.MODULE, NodeType.PACKAGE, NodeType.SERVICE, NodeType.LAYER, NodeType.RESOURCE),
            nodePriority = ::architectureNodePriority,
            edgePriority = ::architectureEdgePriority,
        )
        val visibleGraph = visibleWindow.graph
        val hiddenNodeCount = (fullGraph.nodes.size - visibleGraph.nodes.size).coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = (fullGraph.edges.size - visibleGraph.edges.size).coerceAtLeast(visibleWindow.hiddenEdgeCount)
        return ArchitectureGraphViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = selectArchitectureAnchorNodeId(visibleGraph),
            summary = ArchitectureGraphSummary(
                moduleCount = visibleGraph.nodes.count { it.type == NodeType.MODULE },
                packageCount = visibleGraph.nodes.count { it.type == NodeType.PACKAGE },
                serviceCount = visibleGraph.nodes.count { it.type == NodeType.SERVICE },
                resourceCount = visibleGraph.nodes.count { it.type == NodeType.RESOURCE },
                layerCount = visibleGraph.nodes.count { it.type == NodeType.LAYER },
                relationCount = visibleGraph.edges.size,
                classCount = index.symbolIndex.classesByQualifiedName.size,
                truncated = index.graph.truncated || visibleWindow.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
            ),
            projectionIndex = readonlyProjectionIndex(visibleGraph),
        )
    }

    internal fun graphDocument(
        index: ArchitectureGraphIndex,
        nodes: List<ArchitectureNode>,
        includeClassEdges: Boolean,
        viewMode: AnalysisDisplayMode,
    ): GraphDocument {
        val nodeIds = nodes.mapTo(linkedSetOf(), ArchitectureNode::id)
        val graphNodes = nodes.map { node -> node.toGraphNode(index, viewMode) }
        val graphEdges = index.graph.edges
            .filter { edge ->
                edge.fromNodeId in nodeIds &&
                    edge.toNodeId in nodeIds &&
                    (includeClassEdges || edge.metadata["architecture.aggregate"] != null || edge.kind in setOf(
                        JvmRelationKind.MODULE_CONTAINS_PACKAGE,
                        JvmRelationKind.PACKAGE_CONTAINS_CLASS,
                        JvmRelationKind.SPI_PROVIDES,
                    ))
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
                    ),
                )
            }
        return GraphDocument(
            nodes = graphNodes.sortedBy(GraphNode::id),
            edges = graphEdges.sortedBy(GraphEdge::id),
        )
    }

    internal fun readonlyProjectionIndex(graph: GraphDocument): GraphProjectionIndex =
        GraphProjectionIndex(
            nodeMappings = graph.nodes.associate { node ->
                node.id to GraphProjectionNodeMapping(
                    projectedNodeId = node.id,
                    mappingKind = GraphProjectionMappingKind.SYNTHETIC_READONLY,
                    canonicalNodeIds = listOf(node.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
            edgeMappings = graph.edges.associate { edge ->
                edge.id to GraphProjectionEdgeMapping(
                    projectedEdgeId = edge.id,
                    mappingKind = GraphProjectionMappingKind.SYNTHETIC_READONLY,
                    canonicalEdgeIds = listOf(edge.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
        )

    private fun ArchitectureNode.toGraphNode(
        index: ArchitectureGraphIndex,
        viewMode: AnalysisDisplayMode,
    ): GraphNode {
        val location = source?.startLine?.let { line -> "${source.displayPath}:$line" } ?: source?.displayPath
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
                putAll(architectureSourceSampleMetadata(index))
            },
        )
    }

    private fun ArchitectureNode.architectureSourceSampleMetadata(
        index: ArchitectureGraphIndex,
    ): Map<String, String> {
        val samples = architectureSourceSamples(index)
        if (samples.isEmpty()) {
            return emptyMap()
        }
        return buildMap {
            put("architecture.sourceSample.count", samples.size.toString())
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
            ArchitectureNodeKind.LAYER -> "Layer aggregate with ${memberClassIds.size} classes"
            ArchitectureNodeKind.PACKAGE -> "Package with ${memberClassIds.size} classes"
            else -> null
        }

    private fun ArchitectureNodeKind.toNodeType(): NodeType =
        when (this) {
            ArchitectureNodeKind.MODULE -> NodeType.MODULE
            ArchitectureNodeKind.PACKAGE -> NodeType.PACKAGE
            ArchitectureNodeKind.CLASS -> NodeType.CLASS
            ArchitectureNodeKind.INTERFACE -> NodeType.INTERFACE
            ArchitectureNodeKind.ENUM -> NodeType.ENUM
            ArchitectureNodeKind.ANNOTATION -> NodeType.ANNOTATION
            ArchitectureNodeKind.RECORD -> NodeType.RECORD
            ArchitectureNodeKind.OBJECT -> NodeType.OBJECT
            ArchitectureNodeKind.SERVICE -> NodeType.SERVICE
            ArchitectureNodeKind.RESOURCE -> NodeType.RESOURCE
            ArchitectureNodeKind.LAYER -> NodeType.LAYER
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
        when (node.type) {
            NodeType.MODULE -> 0
            NodeType.LAYER -> 1
            NodeType.SERVICE -> 2
            NodeType.PACKAGE -> 3
            NodeType.RESOURCE -> 4
            else -> 5
        }

    private fun architectureEdgePriority(edge: GraphEdge): Int =
        when (edge.metadata["architecture.aggregate"]) {
            "LAYER" -> 0
            "SERVICE" -> 1
            "PACKAGE" -> 2
            else -> when (edge.metadata["jvm.relation.kind"]) {
                JvmRelationKind.MODULE_CONTAINS_PACKAGE.name -> 3
                JvmRelationKind.SPI_PROVIDES.name -> 4
                else -> 5
            }
        }

    private fun selectArchitectureAnchorNodeId(graph: GraphDocument): String? {
        val sourceBackedAggregateTypes = listOf(
            NodeType.LAYER,
            NodeType.SERVICE,
            NodeType.PACKAGE,
            NodeType.RESOURCE,
        )
        sourceBackedAggregateTypes.forEach { nodeType ->
            graph.nodes.firstOrNull { node ->
                node.type == nodeType && node.hasArchitectureSourceSamples()
            }?.let { return it.id }
        }
        return graph.nodes.firstOrNull { it.type == NodeType.MODULE }?.id ?: graph.nodes.firstOrNull()?.id
    }

    private fun GraphNode.hasArchitectureSourceSamples(): Boolean =
        metadata["architecture.sourceSample.count"]?.toIntOrNull()?.let { count -> count > 0 } == true

    private data class ArchitectureSourceSample(
        val nodeId: String,
        val source: JvmSourceRef,
        val reason: String,
    )

    private companion object {
        private const val MAX_ARCHITECTURE_SOURCE_SAMPLES = 8
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
        JvmRelationKind.FEIGN_ROUTES_TO -> EdgeType.ROUTES_TO
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
        JvmRelationKind.FEIGN_ROUTES_TO -> "routes"
        JvmRelationKind.MQ_PUBLISHES -> "publishes"
        JvmRelationKind.MQ_CONSUMES -> "consumes"
        JvmRelationKind.RESOURCE_BINDS -> "binds"
    }
