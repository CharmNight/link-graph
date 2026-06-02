package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.source.SourceOrigin

class ArchitectureGraphBuilder(
    private val classifier: ArchitectureBoundaryClassifier = ArchitectureBoundaryClassifier(),
) {
    fun build(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget? = null,
    ): ArchitectureGraph {
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val projectClasses = symbolIndex.classesByQualifiedName.values
        val serviceBoundaryNames = ArchitectureBoundaryClassifier.trustedServiceBoundaryNames(projectClasses)
        val projectionTargets = ArchitectureProjectionTargetCache(symbolIndex, classifier, serviceBoundaryNames)
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

        serviceNodes(symbolIndex, serviceBoundaryNames).forEach { serviceNode ->
            nodes[serviceNode.id] = serviceNode
        }
        componentNodes(symbolIndex, serviceBoundaryNames, projectionTargets).forEach { componentNode ->
            nodes[componentNode.id] = componentNode
        }
        resourceGroupNodes(symbolIndex).forEach { resourceNode ->
            nodes[resourceNode.id] = resourceNode
        }
        dependencyGroupNodes(symbolIndex, serviceBoundaryNames).forEach { dependencyNode ->
            nodes[dependencyNode.id] = dependencyNode
        }
        layerNodes(symbolIndex).forEach { layerNode ->
            nodes[layerNode.id] = layerNode
        }

        val edges = buildEdges(symbolIndex, relationIndex, nodes, serviceBoundaryNames, projectionTargets)
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
        serviceBoundaryNames: Map<String, String>,
        projectionTargets: ArchitectureProjectionTargetCache,
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
        val projectionEdges = aggregateRelationsToProjectionTargets(
            symbolIndex = symbolIndex,
            relationIndex = relationIndex,
            nodes = nodes,
            level = ArchitectureAggregationLevel.OVERVIEW,
            serviceBoundaryNames = serviceBoundaryNames,
            projectionTargets = projectionTargets,
        ) + aggregateRelationsToProjectionTargets(
            symbolIndex = symbolIndex,
            relationIndex = relationIndex,
            nodes = nodes,
            level = ArchitectureAggregationLevel.PACKAGE,
            serviceBoundaryNames = serviceBoundaryNames,
            projectionTargets = projectionTargets,
        )
        val layerEdges = aggregateClassRelationsToLayers(symbolIndex, relationIndex, nodes)
        return (directEdges + projectionEdges + layerEdges)
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

    private fun aggregateRelationsToProjectionTargets(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
        level: ArchitectureAggregationLevel,
        serviceBoundaryNames: Map<String, String>,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            if (relation.kind !in architectureAggregateRelationKinds) {
                return@mapNotNull null
            }
            val fromTarget = projectionTargetFor(symbolIndex, relation.fromSymbolId, level, projectionTargets) ?: return@mapNotNull null
            val toTarget = projectionTargetFor(symbolIndex, relation.toSymbolId, level, projectionTargets) ?: return@mapNotNull null
            if (fromTarget.nodeId == toTarget.nodeId || !nodes.containsKey(fromTarget.nodeId) || !nodes.containsKey(toTarget.nodeId)) {
                return@mapNotNull null
            }
            relation.toAggregateEdge(
                prefix = "arch:${level.name.lowercase()}",
                fromNodeId = fromTarget.nodeId,
                toNodeId = toTarget.nodeId,
                metadata = mapOf(
                    "architecture.aggregate" to aggregateName(fromTarget, toTarget),
                    "architecture.aggregate.level" to level.name,
                    "architecture.fromTargetKind" to fromTarget.kind.name,
                    "architecture.toTargetKind" to toTarget.kind.name,
                    "architecture.fromTarget" to fromTarget.qualifiedName,
                    "architecture.toTarget" to toTarget.qualifiedName,
                ),
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
            if (!classifier.isProjectSourceClass(fromClass) || !classifier.isProjectSourceClass(toClass)) {
                return@mapNotNull null
            }
            val fromLayer = classifier.layerFor(fromClass).nodeId
            val toLayer = classifier.layerFor(toClass).nodeId
            if (fromLayer == toLayer || !nodes.containsKey(fromLayer) || !nodes.containsKey(toLayer)) {
                return@mapNotNull null
            }
            relation.toAggregateEdge(
                prefix = "arch:layer",
                fromNodeId = fromLayer,
                toNodeId = toLayer,
                metadata = mapOf(
                    "architecture.aggregate" to "LAYER",
                    "architecture.aggregate.level" to ArchitectureAggregationLevel.OVERVIEW.name,
                ),
            )
        }
    }

    private fun projectionTargetFor(
        symbolIndex: JvmSymbolIndex,
        symbolId: String,
        level: ArchitectureAggregationLevel,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): ArchitectureProjectionTarget? {
        val symbol = symbolIndex.symbolsById[symbolId] ?: return null
        return when (symbol) {
            is JvmClassSymbol -> projectionTargetForClass(symbolIndex, symbol, level, projectionTargets)
            is JvmMethodSymbol -> symbolIndex.findClass(symbol.ownerClassName)
                ?.let { cls -> projectionTargetForClass(symbolIndex, cls, level, projectionTargets) }
            is JvmFieldSymbol -> symbolIndex.findClass(symbol.ownerClassName)
                ?.let { cls -> projectionTargetForClass(symbolIndex, cls, level, projectionTargets) }
            is com.charmnight.linkgraph.jvm.index.JvmResourceSymbol -> projectionTargetForResource(symbol)
            else -> null
        }
    }

    private fun projectionTargetForClass(
        symbolIndex: JvmSymbolIndex,
        cls: JvmClassSymbol,
        level: ArchitectureAggregationLevel,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): ArchitectureProjectionTarget {
        val target = when (level) {
            ArchitectureAggregationLevel.OVERVIEW -> projectionTargets.overviewFor(cls)
            ArchitectureAggregationLevel.PACKAGE -> when {
                cls.jdk || cls.external || cls.library -> projectionTargets.overviewFor(cls)
                else -> classifier.packageGroupFor(cls)
            }
        }
        if (target.kind != ArchitectureProjectionTargetKind.PROJECT_PACKAGE) {
            return target
        }
        val pkg = symbolIndex.packagesByName[cls.packageName] ?: return target
        return target.copy(
            nodeId = pkg.id,
            qualifiedName = pkg.qualifiedName,
            title = pkg.qualifiedName.ifBlank { "(default package)" },
        )
    }

    private fun projectionTargetForResource(
        resource: com.charmnight.linkgraph.jvm.index.JvmResourceSymbol,
    ): ArchitectureProjectionTarget {
        return classifier.resourceGroupFor(resource.path, resource.simpleName)
    }

    private fun aggregateName(
        fromTarget: ArchitectureProjectionTarget,
        toTarget: ArchitectureProjectionTarget,
    ): String =
        when {
            fromTarget.kind == ArchitectureProjectionTargetKind.JDK_GROUP || toTarget.kind == ArchitectureProjectionTargetKind.JDK_GROUP -> "JDK"
            fromTarget.kind == ArchitectureProjectionTargetKind.EXTERNAL_LIBRARY_GROUP || toTarget.kind == ArchitectureProjectionTargetKind.EXTERNAL_LIBRARY_GROUP -> "LIBRARY"
            fromTarget.kind == ArchitectureProjectionTargetKind.PROJECT_SERVICE_BOUNDARY || toTarget.kind == ArchitectureProjectionTargetKind.PROJECT_SERVICE_BOUNDARY -> "SERVICE"
            fromTarget.kind == ArchitectureProjectionTargetKind.PROJECT_COMPONENT || toTarget.kind == ArchitectureProjectionTargetKind.PROJECT_COMPONENT -> "COMPONENT"
            fromTarget.kind == ArchitectureProjectionTargetKind.PROJECT_RESOURCE || toTarget.kind == ArchitectureProjectionTargetKind.PROJECT_RESOURCE -> "RESOURCE"
            fromTarget.kind == ArchitectureProjectionTargetKind.PROJECT_LAYER || toTarget.kind == ArchitectureProjectionTargetKind.PROJECT_LAYER -> "LAYER"
            else -> "PACKAGE"
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

    private fun serviceNodes(
        symbolIndex: JvmSymbolIndex,
        serviceBoundaryNames: Map<String, String>,
    ): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .mapNotNull { cls -> classifier.serviceBoundaryFor(cls, serviceBoundaryNames)?.let { target -> target to cls } }
            .groupBy({ (target, _) -> target }, { (_, cls) -> cls })
            .map { (nodeId, classes) ->
                val packageName = nodeId.qualifiedName
                ArchitectureNode(
                    id = nodeId.nodeId,
                    kind = ArchitectureNodeKind.SERVICE,
                    qualifiedName = packageName,
                    title = nodeId.title,
                    moduleName = classes.mapNotNull(JvmClassSymbol::moduleName).distinct().singleOrNull(),
                    packageName = packageName,
                    memberClassIds = classes.mapTo(linkedSetOf(), JvmClassSymbol::id),
                    metadata = mapOf(
                        "service.package" to packageName,
                        "architecture.boundary.kind" to nodeId.kind.name,
                        "architecture.inferred" to "true",
                        "architecture.inference.reason" to "PROJECT_SERVICE_BOUNDARY",
                    ),
                )
            }
    }

    private fun componentNodes(
        symbolIndex: JvmSymbolIndex,
        serviceBoundaryNames: Map<String, String>,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .filter(classifier::isProjectSourceClass)
            .filter { cls -> classifier.serviceBoundaryFor(cls, serviceBoundaryNames) == null }
            .mapNotNull { cls ->
                val target = projectionTargets.overviewFor(cls)
                if (target.kind == ArchitectureProjectionTargetKind.PROJECT_COMPONENT) {
                    target to cls
                } else {
                    null
                }
            }
            .groupBy({ (target, _) -> target }, { (_, cls) -> cls })
            .map { (target, classes) ->
                ArchitectureNode(
                    id = target.nodeId,
                    kind = ArchitectureNodeKind.COMPONENT,
                    qualifiedName = target.qualifiedName,
                    title = target.title,
                    moduleName = classes.mapNotNull(JvmClassSymbol::moduleName).distinct().singleOrNull(),
                    packageName = target.qualifiedName,
                    memberClassIds = classes.mapTo(linkedSetOf(), JvmClassSymbol::id),
                    metadata = mapOf(
                        "architecture.boundary.kind" to target.kind.name,
                        "component.package" to target.qualifiedName,
                    ),
                )
            }
    }

    private fun resourceGroupNodes(symbolIndex: JvmSymbolIndex): List<ArchitectureNode> {
        return symbolIndex.resourcesByPath.values
            .map { resource -> classifier.resourceGroupFor(resource.path, resource.simpleName) to resource }
            .groupBy({ (target, _) -> target }, { (_, resource) -> resource })
            .map { (target, resources) ->
                ArchitectureNode(
                    id = target.nodeId,
                    kind = ArchitectureNodeKind.RESOURCE,
                    qualifiedName = target.qualifiedName,
                    title = target.title,
                    memberResourceIds = resources.mapTo(linkedSetOf()) { resource -> resource.id },
                    metadata = mapOf(
                        "architecture.boundary.kind" to target.kind.name,
                        "resource.group" to target.qualifiedName,
                    ),
                )
            }
    }

    private fun dependencyGroupNodes(
        symbolIndex: JvmSymbolIndex,
        serviceBoundaryNames: Map<String, String>,
    ): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .filterNot(classifier::isProjectSourceClass)
            .filter { cls ->
                cls.external ||
                    cls.library ||
                    cls.jdk ||
                    cls.origin != SourceOrigin.PROJECT_SOURCE
            }
            .mapNotNull { cls ->
                val target = classifier.projectNodeForClass(
                    cls = cls,
                    sourceClasses = symbolIndex.classesByQualifiedName.values,
                    serviceBoundaryNames = serviceBoundaryNames,
                )
                if (target.kind in setOf(
                        ArchitectureProjectionTargetKind.EXTERNAL_LIBRARY_GROUP,
                        ArchitectureProjectionTargetKind.JDK_GROUP,
                    )
                ) {
                    target to cls
                } else {
                    null
                }
            }
            .groupBy({ (target, _) -> target }, { (_, cls) -> cls })
            .map { (target, classes) ->
                ArchitectureNode(
                    id = target.nodeId,
                    kind = when (target.kind) {
                        ArchitectureProjectionTargetKind.JDK_GROUP -> ArchitectureNodeKind.JDK
                        else -> ArchitectureNodeKind.LIBRARY
                    },
                    qualifiedName = target.qualifiedName,
                    title = target.title,
                    moduleName = classes.mapNotNull(JvmClassSymbol::moduleName).distinct().singleOrNull(),
                    packageName = target.qualifiedName,
                    memberClassIds = classes.mapTo(linkedSetOf(), JvmClassSymbol::id),
                    metadata = mapOf(
                        "architecture.boundary.kind" to target.kind.name,
                        "dependency.group" to target.qualifiedName,
                    ),
                )
            }
    }

    private fun layerNodes(symbolIndex: JvmSymbolIndex): List<ArchitectureNode> {
        return symbolIndex.classesByQualifiedName.values
            .filter(classifier::isProjectSourceClass)
            .groupBy { cls -> classifier.layerFor(cls) }
            .map { (target, classes) ->
                val layer = target.qualifiedName
                ArchitectureNode(
                    id = target.nodeId,
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

private class ArchitectureProjectionTargetCache(
    private val symbolIndex: JvmSymbolIndex,
    private val classifier: ArchitectureBoundaryClassifier,
    private val serviceBoundaryNames: Map<String, String>,
) {
    private val overviewTargetsByClassName = HashMap<String, ArchitectureProjectionTarget>()
    private val componentTargetsByClassName = classifier.componentTargetsForProjectClasses(
        symbolIndex.classesByQualifiedName.values.filter(classifier::isProjectSourceClass),
    )

    fun overviewFor(cls: JvmClassSymbol): ArchitectureProjectionTarget =
        overviewTargetsByClassName.getOrPut(cls.qualifiedName) {
            classifier.serviceBoundaryFor(cls, serviceBoundaryNames)
                ?: if (classifier.isProjectSourceClass(cls)) {
                    componentTargetsByClassName[cls.qualifiedName] ?: fallbackComponentTargetFor(cls)
                } else {
                    classifier.projectNodeForClass(
                        cls = cls,
                        sourceClasses = symbolIndex.classesByQualifiedName.values,
                        serviceBoundaryNames = serviceBoundaryNames,
                    )
                }
        }

    private fun fallbackComponentTargetFor(cls: JvmClassSymbol): ArchitectureProjectionTarget {
        val componentName = cls.packageName.ifBlank { cls.moduleName ?: "(default)" }
        return ArchitectureProjectionTarget(
            nodeId = ArchitectureBoundaryClassifier.componentNodeId(componentName),
            qualifiedName = componentName,
            title = componentName.substringAfterLast('.').ifBlank { componentName },
            kind = ArchitectureProjectionTargetKind.PROJECT_COMPONENT,
        )
    }
}

private val architectureAggregateRelationKinds = setOf(
    JvmRelationKind.CALLS,
    JvmRelationKind.USES_TYPE,
    JvmRelationKind.INJECTS,
    JvmRelationKind.EXTENDS,
    JvmRelationKind.IMPLEMENTS,
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
    JvmRelationKind.RESOURCE_BINDS,
)
