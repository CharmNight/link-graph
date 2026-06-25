package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.architecture.ClassDiagramSummary
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
import com.charmnight.linkgraph.projection.GraphHiddenBucketProjector
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

/**
 * 类图投影器：把架构索引转换为可展示的 UML 类图视图。
 * 负责从架构索引中圈定锚点类的邻域、过滤类图相关的边、注入成员与展示元数据，
 * 并最终裁剪成适配视口策略的可见图与完整图。
 */
class ClassDiagramProjector(
    /** 负责把架构索引转换为通用 GraphDocument 的底层投影器。 */
    private val architectureProjector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    /** 视口裁剪策略，控制可见图最大节点数与边数。 */
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(maxVisibleNodes = 48, maxVisibleEdges = 96),
    /** 折叠桶投影器，用于把折叠的节点按角色聚合成隐藏桶以便 UI 展示。 */
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) : GraphProjector {
    /**
     * 把架构索引投影为类图结果。
     * 处理流程：解析锚点 -> 圈定邻域 -> 生成完整图（含 UML 成员）-> 视口裁剪 -> 注入展示元数据与折叠桶。
     */
    fun project(
        index: ArchitectureGraphIndex,
        scopeNodeId: String? = null,
        relationCompleteness: String = "COMPLETE",
        request: IndexedGraphRequest = requestClassDiagramRequest(scopeNodeId),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ClassDiagramResult {
        // 当前 scope 节点是否本身就是类类型节点。
        val scopeIsClassLike = scopeNodeId?.let { nodeId -> index.node(nodeId)?.kind in classLikeKinds } == true
        // 显式 scope 模式下被选中的类集合（例如用户选中包节点）。
        val explicitScopedClassIds = scopeNodeId
            ?.takeUnless { scopeIsClassLike }
            ?.let(index::classesInScope)
            ?.mapTo(linkedSetOf()) { cls -> cls.id }
            .orEmpty()
        // 实际作为锚点的类 ID：优先用类类型 scope，其次用显式 scope 的首个类，最后回退到默认锚点。
        val anchorClassId = scopeNodeId
            ?.takeIf { nodeId -> index.node(nodeId)?.kind in classLikeKinds }
            ?: explicitScopedClassIds.firstOrNull()
            ?: defaultAnchorClassId(index)
        // 进入类图可见范围的全部类 ID：显式 scope 模式下包含外部一跳，否则围绕锚点扩展邻居。
        val scopedClassIds = if (explicitScopedClassIds.isNotEmpty()) {
            explicitScopeClassIdsWithExternalOneHop(index, explicitScopedClassIds)
        } else {
            classNeighborhoodIds(index, anchorClassId, request.classDiagram.neighborhoodLimit)
        }
        // 候选类型总数，用于判断邻居扩展是否被截断。
        val neighborhoodCandidateTypeCount = if (explicitScopedClassIds.isEmpty()) {
            classNeighborhoodCandidateTypeCount(index, anchorClassId)
        } else {
            scopedClassIds.size
        }
        // 邻居扩展是否因为超出 neighborhoodLimit 被截断。
        val neighborhoodTruncated = explicitScopedClassIds.isEmpty() &&
            neighborhoodCandidateTypeCount > scopedClassIds.size
        // 进入完整图的类类型节点列表。
        val nodes = index.graph.nodes.filter { node ->
            node.kind in classLikeKinds && node.id in scopedClassIds
        }
        // 完整图：包含 UML 成员、类图边类型、展示元数据，是问答/导出等后台操作的基线。
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
        // 主图：从完整图中去除纯签名噪声并裁剪成可读邻域，作为视口裁剪的输入。
        val primaryGraph = fullGraph
            .withoutSignatureOnlyNoiseNodes(anchorClassId ?: scopeNodeId)
            .readableClassDiagramProjection(anchorClassId ?: scopeNodeId)
        // 视口裁剪窗口，按预算保留最重要的节点与边。
        val visibleWindow = primaryGraph.visibleWindow(
            policy = request.classDiagramViewportPolicy(),
            anchorNodeId = anchorClassId ?: scopeNodeId,
            seedNodeTypes = setOf(NodeType.CLASS, NodeType.INTERFACE),
            nodePriority = ::classDiagramNodePriority,
            edgePriority = ::classDiagramEdgePriority,
        )
        // 视口裁剪后的图文档。
        val windowGraph = visibleWindow.graph
        // 最终生效的锚点节点 ID：在窗口中存在的锚点类，依次回退到 scope、首个类、首个节点。
        val anchorNodeId = anchorClassId?.takeIf { nodeId -> windowGraph.nodes.any { it.id == nodeId } }
            ?: scopeNodeId?.takeIf { nodeId -> windowGraph.nodes.any { it.id == nodeId } }
            ?: windowGraph.nodes.firstOrNull { it.type.name == "CLASS" }?.id
            ?: windowGraph.nodes.firstOrNull()?.id
        // 补齐展示元数据并合并平行关系后的最终可见图。
        val visibleGraphWithPresentation = windowGraph
            .withMissingClassDiagramPresentationMetadata(anchorNodeId)
            .aggregateParallelClassDiagramRelations()
        // 比较可见图与完整图计算折叠数量。
        val hiddenCounts = graphProjectionHiddenCounts(
            visibleGraph = visibleGraphWithPresentation,
            fullGraph = fullGraph,
        )
        // 隐藏节点总数，取窗口与全图统计的较大值。
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        // 隐藏边总数，取窗口与全图统计的较大值。
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        // 整体是否被截断：索引/窗口/邻居扩展任一截断，或存在隐藏元素均视为截断。
        val truncated = index.graph.truncated ||
            visibleWindow.truncated ||
            neighborhoodTruncated ||
            hiddenNodeCount > 0 ||
            hiddenEdgeCount > 0
        // 当关系完整度标记为 PARTIAL 时，把请求中的完整度降级为仅结构，避免误导 UI。
        val effectiveRequest = request.copy(
            completeness = if (relationCompleteness == ClassDiagramFastIndex.RELATION_COMPLETENESS_PARTIAL) {
                IndexedGraphCompleteness.StructureOnly
            } else {
                request.completeness
            },
        )
        return ClassDiagramResult(
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

    /** 在未指定 scope 时挑选默认锚点类：优先关系数最多、命名上更像入口的类。 */
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

    /** 计算某个类节点参与类图的关系条数，作为选择默认锚点的关键评分。 */
    private fun classRelationScore(
        index: ArchitectureGraphIndex,
        classNodeId: String,
    ): Int =
        (index.incoming(classNodeId) + index.outgoing(classNodeId)).count { edge ->
            ClassDiagramRelationPolicy.participatesInClassDiagram(edge) &&
                index.node(edge.fromNodeId)?.kind in classLikeKinds &&
                index.node(edge.toNodeId)?.kind in classLikeKinds
        }

    /** 围绕锚点类按关系优先级圈定邻居，超过 neighborhoodLimit 时按优先级截断。 */
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

    /** 统计锚点邻居扩展如果不截断时可达的全部候选类型数，用于判断是否需要标记 truncated。 */
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

    /** 显式 scope 模式下补充外部/JDK/库的一跳类，让类图能够展示依赖的外部类型。 */
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

    /** 把通用图边重新分类为 UML 类图关系边，写入 uml.relation.kind 与展示标签。 */
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

    /** 给类图节点注入展示元数据（角色、泳道、优先级、紧凑标志）以及进入类图的原因。 */
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

    /** 仅在节点缺少展示元数据时补充，避免在已经处理过的图上重复计算。 */
    private fun GraphDocument.withMissingClassDiagramPresentationMetadata(anchorNodeId: String?): GraphDocument =
        if (nodes.all { node -> node.metadata["presentation.role"] != null }) {
            this
        } else {
            withClassDiagramPresentationMetadata(anchorNodeId)
        }

    /** 去除只参与“纯签名”噪声关系的节点，让类图聚焦在真实有意义的关系上。 */
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

    /** 围绕锚点保留可读的类图邻域：先取锚点的强相关边，不足时再回退到更宽松的关系集合。 */
    private fun GraphDocument.readableClassDiagramProjection(anchorNodeId: String?): GraphDocument {
        val anchorNode = resolveReadableAnchorNode(anchorNodeId) ?: return this
        val nodeById = nodes.associateBy(GraphNode::id)
        val selectedNodeIds = linkedSetOf(anchorNode.id)
        val selectedEdges = mutableListOf<GraphEdge>()
        edges
            .asSequence()
            .filter { edge -> edge.fromNodeId in nodeById && edge.toNodeId in nodeById }
            .filter { edge -> edge.isReadableAnchorRelation(anchorNode.id) }
            .filterNot { edge -> edge.isIncomingNonHierarchyAnchorRelation(anchorNode.id) }
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
                .filter { edge -> edge.isReadableClassDiagramRelation() }
                .filterNot { edge -> edge.isIncomingNonHierarchyAnchorRelation(anchorNode.id) }
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
                .filterNot { edge -> edge.isIncomingNonHierarchyAnchorRelation(anchorNode.id) }
                .sortedBy { edge -> edge.classDiagramRelationSortKey() },
        )
    }

    /** 判断是否为指向锚点但属于非层级结构关联的边，这类边在类图中常被折叠。 */
    private fun GraphEdge.isIncomingNonHierarchyAnchorRelation(anchorNodeId: String): Boolean =
        toNodeId == anchorNodeId && !isHierarchyRelation() && isStructuralAssociationRelation()

    /** 解析作为可读投影中心的锚点节点，依次回退到 ANCHOR 角色节点、首个类节点、首个节点。 */
    private fun GraphDocument.resolveReadableAnchorNode(anchorNodeId: String?): GraphNode? =
        anchorNodeId
            ?.let { nodeId -> nodes.firstOrNull { node -> node.id == nodeId } }
            ?: nodes.firstOrNull { node -> node.metadata["presentation.role"] == "ANCHOR" }
            ?: nodes.firstOrNull { node -> node.type == NodeType.CLASS }
            ?: nodes.firstOrNull()

    /** 返回当前边中相对锚点的另一端节点 ID。 */
    private fun GraphEdge.peerNodeId(anchorNodeId: String): String =
        if (fromNodeId == anchorNodeId) toNodeId else fromNodeId

    /** 判断当前边是否与锚点相连。 */
    private fun GraphEdge.isAnchorRelation(anchorNodeId: String): Boolean =
        fromNodeId == anchorNodeId || toNodeId == anchorNodeId

    /** 判断是否为锚点相关且在类图中可读的边。 */
    private fun GraphEdge.isReadableAnchorRelation(anchorNodeId: String): Boolean {
        if (!isAnchorRelation(anchorNodeId)) {
            return false
        }
        return isReadableClassDiagramRelation()
    }

    /** 判断边在类图中是否属于可读关系：层级、字段支撑或权重足够的方法依赖。 */
    private fun GraphEdge.isReadableClassDiagramRelation(): Boolean {
        if (isNoisyDefaultRelation()) {
            return false
        }
        if (isHierarchyRelation() || isFieldBackedRelation()) {
            return true
        }
        return when (classDiagramRelationKind()) {
            ClassDiagramRelationRole.METHOD_CALL.name -> relationWeight() >= MIN_READABLE_DEPENDENCY_WEIGHT
            else -> false
        }
    }

    /** 兜底可见关系：与锚点相关且本身是可读关系。 */
    private fun GraphEdge.isFallbackVisibleRelation(anchorNodeId: String): Boolean =
        isAnchorRelation(anchorNodeId) && isReadableClassDiagramRelation()

    /** 判断是否为默认噪声关系（如局部类型、throws），这类关系默认不展示。 */
    private fun GraphEdge.isNoisyDefaultRelation(): Boolean =
        classDiagramRelationKind() in noisyDefaultRelationKinds

    /** 计算可读关系展示优先级：关系权重 + 角色/层级加成 + 指向锚点的方向加成。 */
    private fun GraphEdge.readableRelationPriority(anchorNodeId: String): Int {
        val directionBonus = if (toNodeId == anchorNodeId) 3 else 0
        val roleBonus = when {
            isHierarchyRelation() -> 30
            isStructuralAssociationRelation() -> 20
            else -> 0
        }
        return relationWeight() + roleBonus + directionBonus
    }

    /** 生成边的排序键，统一类图中边的展示顺序。 */
    /** GraphEdge.classDiagramRelationSortKey 已抽到 top-level（详见 ClassDiagramProjectorHelpers.kt）。 */

    /** 返回类图关系类型字符串（详见 top-level fun classDiagramRelationKind）。 */
    /** 推导 UML 关系类型（详见 top-level fun classDiagramUmlRelationKind）。 */
    /** 解析关系角色枚举（详见 top-level fun classDiagramRelationRole）。 */
    /** 计算关系展示权重（详见 top-level fun relationWeight）。 */

    /** 判断是否为层级关系（继承、实现）。 */
    private fun GraphEdge.isHierarchyRelation(): Boolean =
        classDiagramRelationKind() in hierarchyRelationKinds

    /** 判断是否为结构性关联关系（字段、构造参数、组合、聚合等）。 */
    private fun GraphEdge.isStructuralAssociationRelation(): Boolean =
        classDiagramRelationKind() in structuralAssociationRelationKinds

    /** 判断是否为字段支撑关系：字段直接持有、构造参数赋值给字段、或 UML 类型本身是关联类。 */
    private fun GraphEdge.isFieldBackedRelation(): Boolean =
        when (classDiagramRelationRole()) {
            ClassDiagramRelationRole.FIELD -> metadata[ClassDiagramRelationExtractor.HELD_BY_FIELD_KEY] != "false"
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER ->
                metadata[ClassDiagramRelationExtractor.FIELD_ASSIGNED_KEY] == "true"
            else -> classDiagramUmlRelationKind() in setOf(
                UmlClassRelationKind.COMPOSITION.name,
                UmlClassRelationKind.AGGREGATION.name,
                UmlClassRelationKind.ASSOCIATION.name,
            )
        }

    /** 计算关系权重（详见 top-level fun relationWeight）。 */

    /** 判断是否为纯签名噪声关系：方法参数未被使用，或构造参数既未被使用也未赋值给字段。 */
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

    /** 把同源同终点的平行关系合并成一条聚合边，附带被合并的原始边 ID 与次级标签列表。 */
    private fun GraphDocument.aggregateParallelClassDiagramRelations(): GraphDocument {
        val aggregatedEdges = edges
            .groupBy { edge -> edge.fromNodeId to edge.toNodeId }
            .values
            .map(::aggregateClassDiagramRelationGroup)
            .sortedBy { edge -> edge.classDiagramRelationSortKey() }
        return copy(edges = aggregatedEdges)
    }

    /** 把一组同源同终点的边合并为一条聚合边，保留主关系标签并把其余标签汇总为次级标签。 */
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

    /** 根据源/终点节点 ID 生成稳定的聚合边 ID：详见 top-level fun aggregateRelationId。 */
    /** 生成聚合后的展示标签：详见 top-level fun aggregateRelationLabel。 */
    /** 取聚合组中优先级最高的一条边的展示标签：详见 top-level fun aggregatePrimaryRelationLabel。 */
    /** 取聚合组中除主标签外的次级标签列表：详见 top-level fun aggregateSecondaryRelationLabels。 */
    /** 汇总聚合组中所有边的展示标签并去重：详见 top-level fun aggregateRelationLabels。 */
    /** 返回类图边上对外展示的文本：详见 top-level fun GraphEdge.classDiagramDisplayLabel。 */
    /** 返回关系标签文本：详见 top-level fun GraphEdge.classDiagramRelationLabel。 */

    /** 把投影后的图包装为只读投影索引，向 UI 声明节点/边不可编辑。 */
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

    /** 根据节点与锚点的关系判断展示角色（锚点、抽象接口、调用方、协作对象、输出类型等）。 */
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

    /** 计算每个相邻节点之所以出现在类图中的原因，基于其与锚点间权重最高的关系标签。 */
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

    /** 用关系标签和成员名拼接节点出现原因的展示文本。 */
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

    /** 组装类图展示视图：目标信息、泳道列表、折叠桶以及交互控件。 */
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

    /** 类图节点的展示角色描述：泳道 ID、角色名、展示优先级、是否紧凑模式。 */
    private data class ClassDiagramPresentationRole(
        val laneId: String,
        val role: String,
        val priority: Int,
        val compact: Boolean = true,
    ) {
        /** 把展示角色字段序列化为节点 metadata，供前端读取展示。 */
        fun toMetadata(): Map<String, String> =
            mapOf(
                "presentation.role" to role,
                "presentation.laneId" to laneId,
                "presentation.priority" to priority.toString(),
                "presentation.compact" to compact.toString(),
            )
    }

    /** 给每个类节点注入 UML 字段与方法成员文本，以及类级元数据（abstract、kind、注释等）。 */
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
                node.withUmlClassMetadata(classSymbol, fields, methods)
            },
        )
    }

    /** 把类符号、字段列表、方法列表汇总写入单个节点的 metadata，用于 UI 渲染 UML 类图卡片。 */
    private fun GraphNode.withUmlClassMetadata(
        classSymbol: com.charmnight.linkgraph.jvm.index.JvmClassSymbol?,
        fields: List<JvmFieldSymbol>,
        methods: List<JvmMethodSymbol>,
    ): GraphNode {
        val visibleFields = fields.map(::umlFieldText)
        val visibleMethods = methods.map(::umlMethodText)
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
                put("uml.field.hiddenCount", "0")
                put("uml.method.hiddenCount", "0")
            },
        )
    }

    /** 生成单个 UML 字段的展示文本（字段名 : 类型）。 */
    private fun umlFieldText(field: JvmFieldSymbol): String =
        listOfNotNull(field.simpleName, field.typeName?.let(::shortTypeName))
            .joinToString(": ")

    /** 生成单个 UML 方法的展示文本（方法名(参数) : 返回类型）。 */
    private fun umlMethodText(method: JvmMethodSymbol): String {
        val parameters = method.parameterTypes.joinToString(", ") { type -> shortTypeName(type) }
        val returnType = method.returnType?.let(::shortTypeName)
        val signature = "${method.simpleName}($parameters)"
        return returnType?.let { "$signature: $it" } ?: signature
    }

    /** 把全限定类型名简化为短名（去掉包前缀），便于类图展示。 */
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

    /** 类图节点展示优先级：类/接口优先于枚举等特殊类型，其他类型最低。 */
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

    /** 类图边展示优先级：基于权重取负值或按 UML 关系类型分级，权重越高优先级越高。 */
    /** classDiagramEdgePriority / classAnchorPriority 已抽到 top-level（详见 ClassDiagramProjectorHelpers.kt）。 */

    /** 解析请求中的视口策略；若请求未指定则回退到构造时传入的默认策略。 */
    private fun IndexedGraphRequest.classDiagramViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: viewportPolicy.maxVisibleNodes,
            maxVisibleEdges = viewport.maxVisibleEdges ?: viewportPolicy.maxVisibleEdges,
        )

    private companion object {
        // 视为类图核心类型的架构节点种类集合。
        private val classLikeKinds = setOf(
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
        )
        // 方法依赖类关系视为可读所需的最低权重阈值。
        private const val MIN_READABLE_DEPENDENCY_WEIGHT = 55
        // 视为层级结构（继承/实现）的关系类型集合。
        private val hierarchyRelationKinds = setOf(
            ClassDiagramRelationRole.EXTENDS.name,
            ClassDiagramRelationRole.IMPLEMENTS.name,
            UmlClassRelationKind.GENERALIZATION.name,
            UmlClassRelationKind.REALIZATION.name,
            "EXTENDS",
            "IMPLEMENTS",
        )
        // 视为结构性关联的关系类型集合（字段、构造参数、组合、聚合、关联）。
        private val structuralAssociationRelationKinds = setOf(
            ClassDiagramRelationRole.FIELD.name,
            ClassDiagramRelationRole.CONSTRUCTOR_PARAMETER.name,
            UmlClassRelationKind.COMPOSITION.name,
            UmlClassRelationKind.AGGREGATION.name,
            UmlClassRelationKind.ASSOCIATION.name,
            "FIELD",
            "CONSTRUCTOR_PARAMETER",
        )
        // 默认视为噪声的关系类型集合（局部类型、throws），这些关系默认不展示。
        private val noisyDefaultRelationKinds = setOf(
            ClassDiagramRelationRole.LOCAL_TYPE.name,
            ClassDiagramRelationRole.THROWS.name,
            "LOCAL_TYPE",
            "THROWS",
        )
        /** 类图关系排序键 ClassDiagramRelationSortKey 已抽到 top-level（详见 ClassDiagramProjectorHelpers.kt）。 */

        /** 类图固定的展示泳道列表，按角色把节点划入抽象/调用方/锚点/协作/输出区域。 */
        private fun classDiagramPresentationLanes(): List<GraphPresentationLane> =
            listOf(
                GraphPresentationLane("abstraction", "抽象与接口", GraphPresentationLaneAxis.ZONE, 10, "INTERFACE"),
                GraphPresentationLane("caller", "调用方", GraphPresentationLaneAxis.ZONE, 20, "CALLER"),
                GraphPresentationLane("anchor", "当前类", GraphPresentationLaneAxis.ZONE, 30, "ANCHOR"),
                GraphPresentationLane("collaborator", "协作对象", GraphPresentationLaneAxis.ZONE, 40, "COLLABORATOR"),
                GraphPresentationLane("output", "输出类型", GraphPresentationLaneAxis.ZONE, 50, "OUTPUT"),
            )

        /** 把折叠桶 ID 映射为中文展示标签。 */
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
