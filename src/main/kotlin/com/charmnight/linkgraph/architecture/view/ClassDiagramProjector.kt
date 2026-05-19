package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

class ClassDiagramProjector(
    private val architectureProjector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(maxVisibleNodes = 48, maxVisibleEdges = 96),
) {
    fun project(
        index: ArchitectureGraphIndex,
        scopeNodeId: String? = null,
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
            classNeighborhoodIds(index, anchorClassId)
        }
        val nodes = index.graph.nodes.filter { node ->
            node.kind in classLikeKinds && node.id in scopedClassIds
        }
        val fullGraph = filterClassDiagramEdges(
            architectureProjector.graphDocument(
                index = index,
                nodes = nodes,
                includeClassEdges = true,
                viewMode = AnalysisDisplayMode.CLASS_DIAGRAM,
            ).withUmlClassMembers(index),
        )
        val visibleWindow = fullGraph.visibleWindow(
            policy = viewportPolicy,
            anchorNodeId = anchorClassId ?: scopeNodeId,
            seedNodeTypes = setOf(NodeType.CLASS, NodeType.INTERFACE),
            nodePriority = ::classDiagramNodePriority,
            edgePriority = ::classDiagramEdgePriority,
        )
        val visibleGraph = visibleWindow.graph
        return ClassDiagramViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorClassId?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
                ?: scopeNodeId?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
                ?: visibleGraph.nodes.firstOrNull { it.type.name == "CLASS" }?.id
                ?: visibleGraph.nodes.firstOrNull()?.id,
            summary = ClassDiagramSummary(
                classCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.CLASS.name },
                fieldCount = visibleGraph.nodes.sumOf { node -> node.metadata["uml.field.count"]?.toIntOrNull() ?: 0 },
                interfaceCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name },
                enumCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ENUM.name },
                annotationCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.ANNOTATION.name },
                recordCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.RECORD.name },
                objectCount = visibleGraph.nodes.count { it.metadata["jvm.class.kind"] == JvmClassKind.OBJECT.name },
                relationCount = visibleGraph.edges.size,
                spiProviderCount = visibleGraph.edges.count { edge ->
                    edge.metadata["jvm.relation.kind"] in setOf(
                        JvmRelationKind.SPI_PROVIDES.name,
                        JvmRelationKind.SERVICE_LOADER_LOADS.name,
                    )
                },
                reflectionRelationCount = visibleGraph.edges.count { edge ->
                    edge.metadata["jvm.relation.kind"] == JvmRelationKind.REFLECTS_TO.name
                },
                truncated = index.graph.truncated || visibleWindow.truncated,
                hiddenNodeCount = visibleWindow.hiddenNodeCount,
                hiddenEdgeCount = visibleWindow.hiddenEdgeCount,
            ),
            projectionIndex = architectureProjector.readonlyProjectionIndex(visibleGraph),
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
            edge.kind in classDiagramRelationKinds &&
                index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                index.node(edge.toNodeId)?.kind in classLikeKinds
        }

    private fun classNeighborhoodIds(
        index: ArchitectureGraphIndex,
        anchorClassId: String?,
    ): Set<String> {
        val anchorId = anchorClassId ?: return emptySet()
        val selected = linkedSetOf(anchorId)
        val edgeComparator = compareBy<com.charmnight.linkgraph.architecture.ArchitectureEdge>(
            { edge -> classDiagramRelationPriority(edge.kind) },
            { edge -> edge.id },
        )
        (index.incoming(anchorId) + index.outgoing(anchorId))
            .asSequence()
            .filter { edge ->
                edge.kind in classDiagramRelationKinds &&
                    index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                    index.node(edge.toNodeId)?.kind in classLikeKinds
            }
            .sortedWith(edgeComparator)
            .forEach { edge ->
                for (candidateNodeId in listOf(edge.fromNodeId, edge.toNodeId)) {
                    if (selected.size >= DEFAULT_CLASS_NEIGHBORHOOD_LIMIT) {
                        return@forEach
                    }
                    selected += candidateNodeId
                }
        }
        return selected
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
                .filter { edge -> edge.kind in classDiagramRelationKinds }
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

    private fun filterClassDiagramEdges(graph: GraphDocument): GraphDocument {
        return graph.copy(
            edges = graph.edges.filter { edge ->
                edge.metadata["jvm.relation.kind"] in classDiagramRelationKindNames
            },
        )
    }

    private fun GraphDocument.withUmlClassMembers(index: ArchitectureGraphIndex): GraphDocument {
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
                node.withUmlClassMetadata(classSymbol, fields, methods)
            },
        )
    }

    private fun GraphNode.withUmlClassMetadata(
        classSymbol: com.charmnight.linkgraph.jvm.index.JvmClassSymbol?,
        fields: List<JvmFieldSymbol>,
        methods: List<JvmMethodSymbol>,
    ): GraphNode {
        val visibleFields = fields.take(MAX_UML_MEMBERS_PER_COMPARTMENT).map(::umlFieldText)
        val visibleMethods = methods.take(MAX_UML_MEMBERS_PER_COMPARTMENT).map(::umlMethodText)
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
        when (edge.metadata["jvm.relation.kind"]) {
            JvmRelationKind.EXTENDS.name,
            JvmRelationKind.IMPLEMENTS.name,
            -> 0
            JvmRelationKind.INJECTS.name -> 1
            JvmRelationKind.CALLS.name -> 2
            JvmRelationKind.SPI_PROVIDES.name,
            JvmRelationKind.SERVICE_LOADER_LOADS.name,
            -> 3
            JvmRelationKind.REFLECTS_TO.name -> 4
            JvmRelationKind.USES_TYPE.name -> 5
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

    private fun classDiagramRelationPriority(kind: JvmRelationKind): Int =
        when (kind) {
            JvmRelationKind.EXTENDS,
            JvmRelationKind.IMPLEMENTS,
            -> 0
            JvmRelationKind.INJECTS -> 1
            JvmRelationKind.CALLS -> 2
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            -> 3
            JvmRelationKind.REFLECTS_TO -> 4
            JvmRelationKind.USES_TYPE -> 5
            else -> 6
        }

    private companion object {
        private const val DEFAULT_CLASS_NEIGHBORHOOD_LIMIT = 24
        private const val MAX_UML_MEMBERS_PER_COMPARTMENT = 5
        private val classLikeKinds = setOf(
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
        )
        private val classDiagramRelationKinds = setOf(
            JvmRelationKind.EXTENDS,
            JvmRelationKind.IMPLEMENTS,
            JvmRelationKind.USES_TYPE,
            JvmRelationKind.INJECTS,
            JvmRelationKind.CALLS,
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            JvmRelationKind.DUBBO_PROVIDES,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.FEIGN_ROUTES_TO,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.MQ_CONSUMES,
        )
        private val classDiagramRelationKindNames = classDiagramRelationKinds.mapTo(linkedSetOf(), JvmRelationKind::name)
    }
}
