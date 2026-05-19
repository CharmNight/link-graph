package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

class ArchitectureGraphBuilder {
    fun build(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget? = null,
    ): ArchitectureGraph {
        val nodes = linkedMapOf<String, ArchitectureNode>()
        symbolIndex.modulesByName.values.sortedBy { it.qualifiedName }.forEach { module ->
            nodes[module.id] = ArchitectureNode(
                id = module.id,
                kind = ArchitectureNodeKind.MODULE,
                qualifiedName = module.qualifiedName,
                title = module.simpleName,
                source = module.source,
            )
        }
        symbolIndex.packagesByName.values.sortedBy { it.qualifiedName }.forEach { pkg ->
            nodes[pkg.id] = ArchitectureNode(
                id = pkg.id,
                kind = ArchitectureNodeKind.PACKAGE,
                qualifiedName = pkg.qualifiedName,
                title = pkg.qualifiedName.ifBlank { "(default package)" },
                moduleName = pkg.moduleName,
                packageName = pkg.qualifiedName,
                source = pkg.source,
            )
        }
        symbolIndex.classesByQualifiedName.values.sortedBy { it.qualifiedName }.forEach { cls ->
            nodes[cls.id] = ArchitectureNode(
                id = cls.id,
                kind = cls.kind.toArchitectureNodeKind(),
                qualifiedName = cls.qualifiedName,
                title = cls.simpleName,
                moduleName = cls.moduleName,
                packageName = cls.packageName,
                classKind = cls.kind,
                stereotype = cls.stereotype,
                source = cls.source,
                metadata = buildMap {
                    put("class.qualifiedName", cls.qualifiedName)
                    put("class.kind", cls.kind.name)
                    put("class.stereotype", cls.stereotype.name)
                    put("jvm.class.abstract", cls.abstract.toString())
                    cls.docComment?.let { put("jvm.class.docComment", it) }
                },
            )
        }
        symbolIndex.resourcesByPath.values.sortedBy { it.path }.forEach { resource ->
            nodes[resource.id] = ArchitectureNode(
                id = resource.id,
                kind = ArchitectureNodeKind.RESOURCE,
                qualifiedName = resource.path,
                title = resource.simpleName,
                resourceKind = resource.kind,
                source = resource.source,
                metadata = mapOf(
                    "resource.path" to resource.path,
                    "resource.kind" to resource.kind.name,
                ),
            )
        }

        val packageClassIds = symbolIndex.classesByQualifiedName.values
            .groupBy(JvmClassSymbol::packageName)
            .mapValues { (_, classes) -> classes.mapTo(linkedSetOf(), JvmClassSymbol::id) }
        packageClassIds.forEach { (packageName, classIds) ->
            val pkg = symbolIndex.packagesByName[packageName] ?: return@forEach
            nodes[pkg.id] = nodes.getValue(pkg.id).copy(memberClassIds = classIds)
        }
        val packageResourceIds = symbolIndex.resourcesByPath.values
            .groupBy { resource -> resource.path.substringBeforeLast('/', missingDelimiterValue = "") }
            .mapValues { (_, resources) -> resources.mapTo(linkedSetOf()) { resource -> resource.id } }
        packageResourceIds.forEach { (packageName, resourceIds) ->
            val pkg = symbolIndex.packagesByName[packageName] ?: return@forEach
            nodes[pkg.id] = nodes.getValue(pkg.id).copy(memberResourceIds = resourceIds)
        }

        serviceNodes(symbolIndex).forEach { serviceNode ->
            nodes[serviceNode.id] = serviceNode
        }
        layerNodes(symbolIndex).forEach { layerNode ->
            nodes[layerNode.id] = layerNode
        }

        val edges = buildEdges(symbolIndex, relationIndex, nodes)
        val incoming = edges.mapTo(linkedSetOf()) { edge -> edge.toNodeId }
        val roots = nodes.values
            .filter { node -> node.kind == ArchitectureNodeKind.MODULE || node.id !in incoming }
            .map(ArchitectureNode::id)
        return ArchitectureGraph(
            nodes = nodes.values.sortedWith(compareBy({ it.kind.name }, { it.qualifiedName }, { it.id })),
            edges = edges.sortedBy(ArchitectureEdge::id),
            rootNodeIds = roots,
            truncated = budget?.let { resolutionBudget ->
                symbolIndex.classesByQualifiedName.size >= resolutionBudget.maxProjectClasses ||
                    symbolIndex.classesByQualifiedName.values.count { symbol -> symbol.external } >= resolutionBudget.maxExternalClasses ||
                    symbolIndex.methodsBySignature.size >= resolutionBudget.maxMethods ||
                    relationIndex.truncated ||
                    relationIndex.relations.size >= resolutionBudget.maxRelations
            } ?: false,
            metadata = buildMap {
                budget?.let { resolutionBudget ->
                    put("budget.includeTests", resolutionBudget.includeTests.toString())
                    put("budget.includeExternalLibraries", resolutionBudget.includeExternalLibraries.toString())
                    put("budget.includeJdk", resolutionBudget.includeJdk.toString())
                    put("budget.includeUserAttachedJars", resolutionBudget.includeUserAttachedJars.toString())
                    put("budget.maxProjectClasses", resolutionBudget.maxProjectClasses.toString())
                    put("budget.maxExternalClasses", resolutionBudget.maxExternalClasses.toString())
                    put("budget.maxMethods", resolutionBudget.maxMethods.toString())
                    put("budget.maxRelations", resolutionBudget.maxRelations.toString())
                }
                put("budget.relationsTruncated", relationIndex.truncated.toString())
            },
        )
    }

    private fun buildEdges(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
    ): List<ArchitectureEdge> {
        val directEdges = relationIndex.relations.mapNotNull { relation ->
            val fromNodeId = relation.projectedFromNodeId(symbolIndex)
            val toNodeId = relation.projectedToNodeId(symbolIndex)
            if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
                return@mapNotNull null
            }
            ArchitectureEdge(
                id = if (fromNodeId == relation.fromSymbolId && toNodeId == relation.toSymbolId) {
                    relation.id
                } else {
                    "arch:direct:${relation.kind.name.lowercase()}:$fromNodeId->$toNodeId:${relation.id}"
                },
                kind = relation.kind,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                confidence = relation.confidence,
                count = relation.count,
                sourceRelationIds = setOf(relation.id),
                metadata = relation.architectureMetadata(),
            )
        }
        val packageEdges = aggregateClassRelationsToPackages(symbolIndex, relationIndex)
        val serviceEdges = aggregateClassRelationsToServices(symbolIndex, relationIndex, nodes)
        val layerEdges = aggregateClassRelationsToLayers(symbolIndex, relationIndex, nodes)
        val resourceEdges = aggregateResourceRelations(symbolIndex, relationIndex, nodes)
        return (directEdges + packageEdges + serviceEdges + layerEdges + resourceEdges)
            .groupBy(ArchitectureEdge::id)
            .values
            .map(::mergeArchitectureEdges)
    }

    private fun JvmRelation.projectedFromNodeId(symbolIndex: JvmSymbolIndex): String {
        val symbol = symbolIndex.symbolsById[fromSymbolId] ?: return fromSymbolId
        return (symbol as? JvmMethodSymbol)
            ?.ownerClassName
            ?.let(symbolIndex::findClass)
            ?.id ?: (symbol as? JvmFieldSymbol)
            ?.ownerClassName
            ?.let(symbolIndex::findClass)
            ?.id ?: fromSymbolId
    }

    private fun JvmRelation.projectedToNodeId(symbolIndex: JvmSymbolIndex): String {
        val symbol = symbolIndex.symbolsById[toSymbolId] ?: return toSymbolId
        return when (symbol) {
            is JvmMethodSymbol -> symbolIndex.findClass(symbol.ownerClassName)?.id ?: toSymbolId
            is JvmFieldSymbol -> symbolIndex.findClass(symbol.ownerClassName)?.id ?: toSymbolId
            else -> toSymbolId
        }
    }

    private fun aggregateClassRelationsToPackages(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
    ): List<ArchitectureEdge> {
        return relationIndex.relations
            .mapNotNull { relation ->
                val fromClass = ownerClassForRelation(symbolIndex, relation.fromSymbolId) ?: return@mapNotNull null
                val toClass = ownerClassForRelation(symbolIndex, relation.toSymbolId) ?: return@mapNotNull null
                if (fromClass.packageName == toClass.packageName) return@mapNotNull null
                val fromPackage = symbolIndex.packagesByName[fromClass.packageName] ?: return@mapNotNull null
                val toPackage = symbolIndex.packagesByName[toClass.packageName] ?: return@mapNotNull null
                relation.toAggregateEdge(
                    prefix = "arch:package",
                    fromNodeId = fromPackage.id,
                    toNodeId = toPackage.id,
                    metadata = mapOf(
                        "architecture.aggregate" to "PACKAGE",
                        "source.class" to fromClass.qualifiedName,
                        "target.class" to toClass.qualifiedName,
                    ),
                )
            }
    }

    private fun aggregateClassRelationsToServices(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            val fromClass = ownerClassForRelation(symbolIndex, relation.fromSymbolId) ?: return@mapNotNull null
            val toClass = ownerClassForRelation(symbolIndex, relation.toSymbolId) ?: return@mapNotNull null
            val fromService = serviceNodeIdFor(fromClass)
            val toService = serviceNodeIdFor(toClass)
            if (fromService == toService || !nodes.containsKey(fromService) || !nodes.containsKey(toService)) {
                return@mapNotNull null
            }
            relation.toAggregateEdge(
                prefix = "arch:service",
                fromNodeId = fromService,
                toNodeId = toService,
                metadata = mapOf("architecture.aggregate" to "SERVICE"),
            )
        }
    }

    private fun aggregateClassRelationsToLayers(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            val fromClass = ownerClassForRelation(symbolIndex, relation.fromSymbolId) ?: return@mapNotNull null
            val toClass = ownerClassForRelation(symbolIndex, relation.toSymbolId) ?: return@mapNotNull null
            val fromLayer = layerNodeIdFor(fromClass)
            val toLayer = layerNodeIdFor(toClass)
            if (fromLayer == toLayer || !nodes.containsKey(fromLayer) || !nodes.containsKey(toLayer)) {
                return@mapNotNull null
            }
            relation.toAggregateEdge(
                prefix = "arch:layer",
                fromNodeId = fromLayer,
                toNodeId = toLayer,
                metadata = mapOf("architecture.aggregate" to "LAYER"),
            )
        }
    }

    private fun aggregateResourceRelations(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            val fromClass = ownerClassForRelation(symbolIndex, relation.fromSymbolId)
            val toClass = ownerClassForRelation(symbolIndex, relation.toSymbolId)
            val fromResource = symbolIndex.resourcesByPath.values.firstOrNull { resource -> resource.id == relation.fromSymbolId }
            val toResource = symbolIndex.resourcesByPath.values.firstOrNull { resource -> resource.id == relation.toSymbolId }
            val fromNodeId = when {
                fromClass != null -> serviceNodeIdFor(fromClass)
                fromResource != null -> fromResource.id
                else -> return@mapNotNull null
            }
            val toNodeId = when {
                toClass != null -> serviceNodeIdFor(toClass)
                toResource != null -> toResource.id
                else -> return@mapNotNull null
            }
            if (fromNodeId == toNodeId || !nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
                return@mapNotNull null
            }
            relation.toAggregateEdge(
                prefix = "arch:resource",
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                metadata = mapOf("architecture.aggregate" to "RESOURCE"),
            )
        }
    }

    private fun ownerClassForRelation(
        symbolIndex: JvmSymbolIndex,
        symbolId: String,
    ): JvmClassSymbol? {
        return when (val symbol = symbolIndex.symbolsById[symbolId]) {
            is JvmClassSymbol -> symbol
            is JvmMethodSymbol -> symbolIndex.findClass(symbol.ownerClassName)
            is JvmFieldSymbol -> symbolIndex.findClass(symbol.ownerClassName)
            else -> null
        }
    }

    private fun JvmRelation.toAggregateEdge(
        prefix: String,
        fromNodeId: String,
        toNodeId: String,
        metadata: Map<String, String>,
    ): ArchitectureEdge =
        ArchitectureEdge(
            id = "$prefix:${kind.name.lowercase()}:$fromNodeId->$toNodeId",
            kind = kind,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            confidence = confidence,
            count = count,
            sourceRelationIds = setOf(id),
            metadata = architectureMetadata() + metadata,
        )

    private fun JvmRelation.architectureMetadata(): Map<String, String> =
        metadata + mapOf(
            "jvm.relation.kind" to kind.name,
            "jvm.relation.confidence" to confidence.name,
            "jvm.relation.source" to source.name,
            "jvm.relation.count" to count.toString(),
        )

    private fun mergeArchitectureEdges(edges: List<ArchitectureEdge>): ArchitectureEdge {
        val first = edges.first()
        if (edges.size == 1) {
            return first
        }
        return first.copy(
            confidence = edges.map(ArchitectureEdge::confidence).minByOrNull(JvmRelationConfidence::ordinal) ?: first.confidence,
            count = edges.sumOf(ArchitectureEdge::count),
            sourceRelationIds = edges.flatMap(ArchitectureEdge::sourceRelationIds).toSet(),
            metadata = edges.fold(first.metadata) { current, edge -> current + edge.metadata },
        )
    }

    private fun serviceNodes(symbolIndex: JvmSymbolIndex): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .groupBy { cls -> serviceNodeIdFor(cls) }
            .map { (nodeId, classes) ->
                val packageName = serviceNameFor(classes.first())
                ArchitectureNode(
                    id = nodeId,
                    kind = ArchitectureNodeKind.SERVICE,
                    qualifiedName = packageName,
                    title = packageName.substringAfterLast('.').ifBlank { packageName },
                    moduleName = classes.mapNotNull(JvmClassSymbol::moduleName).distinct().singleOrNull(),
                    packageName = packageName,
                    memberClassIds = classes.mapTo(linkedSetOf(), JvmClassSymbol::id),
                    metadata = mapOf("service.package" to packageName),
                )
            }
    }

    private fun layerNodes(symbolIndex: JvmSymbolIndex): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .groupBy { cls -> layerNodeIdFor(cls) }
            .map { (nodeId, classes) ->
                val layer = layerNameFor(classes.first())
                ArchitectureNode(
                    id = nodeId,
                    kind = ArchitectureNodeKind.LAYER,
                    qualifiedName = layer,
                    title = layer,
                    memberClassIds = classes.mapTo(linkedSetOf(), JvmClassSymbol::id),
                    metadata = mapOf("layer.name" to layer),
                )
            }
    }

    private fun JvmClassKind.toArchitectureNodeKind(): ArchitectureNodeKind =
        when (this) {
            JvmClassKind.CLASS -> ArchitectureNodeKind.CLASS
            JvmClassKind.INTERFACE -> ArchitectureNodeKind.INTERFACE
            JvmClassKind.ENUM -> ArchitectureNodeKind.ENUM
            JvmClassKind.ANNOTATION -> ArchitectureNodeKind.ANNOTATION
            JvmClassKind.RECORD -> ArchitectureNodeKind.RECORD
            JvmClassKind.OBJECT -> ArchitectureNodeKind.OBJECT
        }
}

private fun serviceNodeIdFor(cls: JvmClassSymbol): String = "arch:service:${serviceNameFor(cls)}"

private fun layerNodeIdFor(cls: JvmClassSymbol): String = "arch:layer:${layerNameFor(cls).lowercase()}"

private fun serviceNameFor(cls: JvmClassSymbol): String {
    val parts = cls.packageName.split('.').filter(String::isNotBlank)
    if (parts.size <= 3) {
        return cls.packageName.ifBlank { "(default)" }
    }
    val markerIndex = parts.indexOfFirst { part ->
        part in setOf("controller", "web", "api", "service", "domain", "repository", "dao", "mapper", "infra", "infrastructure", "config")
    }
    val serviceParts = if (markerIndex > 1) parts.take(markerIndex) else parts.take((parts.size - 1).coerceAtLeast(1))
    return serviceParts.joinToString(".").ifBlank { cls.packageName.ifBlank { "(default)" } }
}

private fun layerNameFor(cls: JvmClassSymbol): String {
    val packageText = cls.packageName.lowercase()
    val simpleText = cls.simpleName.lowercase()
    return when {
        cls.stereotype == JvmStereotype.CONTROLLER || ".controller" in packageText || ".web" in packageText || ".api" in packageText -> "API"
        cls.stereotype == JvmStereotype.SERVICE || ".service" in packageText || simpleText.endsWith("service") -> "SERVICE"
        cls.stereotype == JvmStereotype.REPOSITORY || ".repository" in packageText || ".dao" in packageText || ".mapper" in packageText -> "DATA"
        cls.stereotype == JvmStereotype.CONFIGURATION || ".config" in packageText -> "CONFIG"
        ".domain" in packageText || ".model" in packageText -> "DOMAIN"
        ".infra" in packageText || ".infrastructure" in packageText -> "INFRA"
        else -> "CORE"
    }
}
