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

/**
 * 架构图构建器，将 JVM 符号索引与关系索引组装为供架构视图消费的图结构。
 * 负责模块/包/类/资源等节点生成，以及直接边、聚合边、分层边的构造与合并。
 */
class ArchitectureGraphBuilder(
    /** 用于推断服务边界、组件、分层等信息的分类器。 */
    private val classifier: ArchitectureBoundaryClassifier = ArchitectureBoundaryClassifier(),
) {
    /**
     * 根据符号索引和关系索引生成完整的架构图。
     * @param budget 解析预算，用于判断结果是否被截断并附带元数据。
     */
    fun build(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        budget: com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget? = null,
    ): ArchitectureGraph {
        // 节点容器，按 ID 索引；遍历时使用 LinkedHashMap 保留插入顺序
        val nodes = linkedMapOf<String, ArchitectureNode>()
        val projectClasses = symbolIndex.classesByQualifiedName.values
        // 通过项目类集合推断可信的服务边界名称映射，避免对每个类重复推断
        val serviceBoundaryNames = ArchitectureBoundaryClassifier.trustedServiceBoundaryNames(projectClasses)
        // 投影目标缓存：避免对同一类反复执行边界推断与组件归属判定
        val projectionTargets = ArchitectureProjectionTargetCache(symbolIndex, classifier, serviceBoundaryNames)
        // 模块节点：按全限定名排序以保证构建结果稳定
        symbolIndex.modulesByName.values.sortedBy { it.qualifiedName }.forEach { module ->
            nodes[module.id] = ArchitectureNode(
                id = module.id,
                kind = ArchitectureNodeKind.MODULE,
                qualifiedName = module.qualifiedName,
                title = module.simpleName,
                source = module.source,
            )
        }
        // 包节点：默认包显示占位标题，避免出现空字符串
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
        // 类节点：将类符号的元信息（kind、stereotype、abstract 等）一并写入节点 metadata
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
        // 资源节点：作为外部配置、SPI 文件等进入架构图的入口
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

        // 将项目类按包聚合，回填到包节点的 memberClassIds
        val packageClassIds = symbolIndex.classesByQualifiedName.values
            .groupBy(JvmClassSymbol::packageName)
            .mapValues { (_, classes) -> classes.mapTo(linkedSetOf(), JvmClassSymbol::id) }
        packageClassIds.forEach { (packageName, classIds) ->
            val pkg = symbolIndex.packagesByName[packageName] ?: return@forEach
            nodes[pkg.id] = nodes.getValue(pkg.id).copy(memberClassIds = classIds)
        }
        // 资源按其所在目录前缀聚合到包，便于在包视图中查看资源
        val packageResourceIds = symbolIndex.resourcesByPath.values
            .groupBy { resource -> resource.path.substringBeforeLast('/', missingDelimiterValue = "") }
            .mapValues { (_, resources) -> resources.mapTo(linkedSetOf()) { resource -> resource.id } }
        packageResourceIds.forEach { (packageName, resourceIds) ->
            val pkg = symbolIndex.packagesByName[packageName] ?: return@forEach
            nodes[pkg.id] = nodes.getValue(pkg.id).copy(memberResourceIds = resourceIds)
        }

        // 不同维度的聚合节点：服务、组件、资源组、依赖组、分层
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
        // 收集所有入边端点，用于判定根节点
        val incoming = edges.mapTo(linkedSetOf()) { edge -> edge.toNodeId }
        // 根节点定义为模块节点或没有任何入边的节点，作为架构树的起点
        val roots = nodes.values
            .filter { node -> node.kind == ArchitectureNodeKind.MODULE || node.id !in incoming }
            .map(ArchitectureNode::id)
        return ArchitectureGraph(
            nodes = nodes.values.sortedWith(compareBy({ it.kind.name }, { it.qualifiedName }, { it.id })),
            edges = edges.sortedBy(ArchitectureEdge::id),
            rootNodeIds = roots,
            // 任一维度超出预算或被关系索引截断时，整体标记为截断
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

    /** 构造图中所有边：直接关系边 + 投影聚合边 + 分层聚合边，最后按 ID 合并。 */
    private fun buildEdges(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
        serviceBoundaryNames: Map<String, String>,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): List<ArchitectureEdge> {
        // 直接边：将关系端点投影到类节点后构造的边，是最细粒度的连接
        val directEdges = relationIndex.relations.mapNotNull { relation ->
            val fromNodeId = relation.projectedFromNodeId(symbolIndex)
            val toNodeId = relation.projectedToNodeId(symbolIndex)
            // 任一端点不存在对应节点时丢弃，避免产生悬空边
            if (!nodes.containsKey(fromNodeId) || !nodes.containsKey(toNodeId)) {
                return@mapNotNull null
            }
            ArchitectureEdge(
                // 若端点未被投影改写，复用原关系 ID；否则按投影后的端点重新生成 ID
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
        // 聚合边：按概览与包两种聚合级别生成，分别用于全局架构视图与按包聚合视图
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
        // 分层聚合边：仅基于项目源码类，体现 API→Service→Data 等分层依赖方向
        val layerEdges = aggregateClassRelationsToLayers(symbolIndex, relationIndex, nodes)
        // 合并同 ID 的边，避免重复连接造成视觉与统计冗余
        return (directEdges + projectionEdges + layerEdges)
            .groupBy(ArchitectureEdge::id)
            .values
            .map(::mergeArchitectureEdges)
    }

/** 把关系的源端符号 ID 投影到其所属类的节点 ID（方法/字段归到所属类）。 */
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

    /** 把关系的目标端符号 ID 投影到其所属类的节点 ID。 */
    private fun JvmRelation.projectedToNodeId(symbolIndex: JvmSymbolIndex): String {
        val symbol = symbolIndex.symbolsById[toSymbolId] ?: return toSymbolId
        return when (symbol) {
            is JvmMethodSymbol -> symbolIndex.findClass(symbol.ownerClassName)?.id ?: toSymbolId
            is JvmFieldSymbol -> symbolIndex.findClass(symbol.ownerClassName)?.id ?: toSymbolId
            else -> toSymbolId
        }
    }

    /** 把关系按指定聚合级别（概览/包级）聚合到投影目标之间，形成聚合边。 */
    private fun aggregateRelationsToProjectionTargets(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
        level: ArchitectureAggregationLevel,
        serviceBoundaryNames: Map<String, String>,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            // 仅对参与聚合的关系类型做投影，过滤掉纯描述性的关系
            if (relation.kind !in architectureAggregateRelationKinds) {
                return@mapNotNull null
            }
            val fromTarget = projectionTargetFor(symbolIndex, relation.fromSymbolId, level, projectionTargets) ?: return@mapNotNull null
            val toTarget = projectionTargetFor(symbolIndex, relation.toSymbolId, level, projectionTargets) ?: return@mapNotNull null
            // 同一投影目标内部或目标节点缺失时不产生边
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

    /** 把项目源码类之间的关系聚合到分层（API/SERVICE/DATA 等）之间。 */
    private fun aggregateClassRelationsToLayers(
        symbolIndex: JvmSymbolIndex,
        relationIndex: JvmRelationIndex,
        nodes: Map<String, ArchitectureNode>,
    ): List<ArchitectureEdge> {
        return relationIndex.relations.mapNotNull { relation ->
            val fromClass = ownerClassForRelation(symbolIndex, relation.fromSymbolId) ?: return@mapNotNull null
            val toClass = ownerClassForRelation(symbolIndex, relation.toSymbolId) ?: return@mapNotNull null
            // 分层视图只关心项目源码类之间的依赖
            if (!classifier.isProjectSourceClass(fromClass) || !classifier.isProjectSourceClass(toClass)) {
                return@mapNotNull null
            }
            val fromLayer = classifier.layerFor(fromClass).nodeId
            val toLayer = classifier.layerFor(toClass).nodeId
            // 同层关系不构边，避免在分层图上形成自环噪声
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

    /** 根据符号 ID 和聚合级别，把符号映射到对应的投影目标。 */
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

    /** 按聚合级别返回类对应的投影目标；包级别下会落到真实包节点。 */
    private fun projectionTargetForClass(
        symbolIndex: JvmSymbolIndex,
        cls: JvmClassSymbol,
        level: ArchitectureAggregationLevel,
        projectionTargets: ArchitectureProjectionTargetCache,
    ): ArchitectureProjectionTarget {
        val target = when (level) {
            // 概览级别直接使用概览投影（可能是服务、组件、库、JDK 等）
            ArchitectureAggregationLevel.OVERVIEW -> projectionTargets.overviewFor(cls)
            // 包级别下，外部/JDK 类仍走概览，项目类则落到具体包
            ArchitectureAggregationLevel.PACKAGE -> when {
                cls.jdk || cls.external || cls.library -> projectionTargets.overviewFor(cls)
                else -> classifier.packageGroupFor(cls)
            }
        }
        // 仅项目包需要把投影目标修正为真实包节点（保留 ID 与名称一致）
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

    /** 把资源符号映射到资源分组投影目标。 */
    private fun projectionTargetForResource(
        resource: com.charmnight.linkgraph.jvm.index.JvmResourceSymbol,
    ): ArchitectureProjectionTarget {
        return classifier.resourceGroupFor(resource.path, resource.simpleName)
    }

    /** 根据两端投影目标种类给出聚合边的可读名称（如 JDK/LIBRARY/SERVICE 等）。 */
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

    /** 取关系端符号所属的类符号（方法/字段归到类）。 */
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

    /** 将关系转换为聚合边（带前缀和自定义元数据）。 */
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

    /** 构造架构图使用的元数据（包含关系种类、置信度、来源与计数）。 */
    private fun JvmRelation.architectureMetadata(): Map<String, String> =
        metadata + mapOf(
            "jvm.relation.kind" to kind.name,
            "jvm.relation.confidence" to confidence.name,
            "jvm.relation.source" to source.name,
            "jvm.relation.count" to count.toString(),
        )

    /** 合并同一 ID 的多条边：取最低置信度等级、累加计数、汇总来源关系和元数据。 */
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

    /** 生成服务边界节点：将归属于服务边界的类聚合并附带成员引用。 */
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

    /** 生成组件节点：未归属服务边界的项目源码类按组件聚合。 */
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

    /** 生成资源分组节点：把资源按分组聚合。 */
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

    /** 生成依赖分组节点：把外部库与 JDK 的类聚合到对应分组。 */
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

    /** 生成分层节点：按类的分层聚合项目源码类。 */
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

    /** 将 JVM 类种类映射为架构节点种类。 */
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

/** 投影目标缓存：避免对同一类重复推断服务边界与组件归属。 */
private class ArchitectureProjectionTargetCache(
    private val symbolIndex: JvmSymbolIndex,
    private val classifier: ArchitectureBoundaryClassifier,
    private val serviceBoundaryNames: Map<String, String>,
) {
    /** 概览投影目标缓存：类限定名 -> 投影目标。 */
    private val overviewTargetsByClassName = HashMap<String, ArchitectureProjectionTarget>()
    /** 预先批量计算的项目类组件归属映射。 */
    private val componentTargetsByClassName = classifier.componentTargetsForProjectClasses(
        symbolIndex.classesByQualifiedName.values.filter(classifier::isProjectSourceClass),
    )

    /** 返回类的概览级投影目标，按需缓存。 */
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

    /** 组件归推断失败时的兜底逻辑：直接以包名或模块名作为组件名。 */
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

/** 允许参与聚合的关系种类集合（如调用、类型使用、扩展、SPI、消息等）。 */
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
    JvmRelationKind.SPRING_ROUTES_TO,
    JvmRelationKind.MQ_PUBLISHES,
    JvmRelationKind.MQ_CONSUMES,
    JvmRelationKind.RESOURCE_BINDS,
)
