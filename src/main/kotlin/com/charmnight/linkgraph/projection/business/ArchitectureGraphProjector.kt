package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphSummary
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
import com.charmnight.linkgraph.projection.GraphHiddenBucketProjector
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
import com.charmnight.linkgraph.model.GraphSourceLocation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.SourceNavigationAnchors
import com.charmnight.linkgraph.model.putSourceLocation
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/**
 * 架构图投影器。
 *
 * 把架构索引（[ArchitectureGraphIndex]）转换为对外可消费的架构图视图：
 * 1) 根据请求的范围与节点种类筛选要展示的结构节点；
 * 2) 把节点/边投影为通用图文档，附带展示元数据；
 * 3) 通过视口策略裁剪可见规模，统计被隐藏的规模；
 * 4) 组装出 [ArchitectureGraphResult]，包含可见图、完整图、锚点、摘要、
 *    投影索引和呈现层信息。
 *
 * 同时提供包视图（按 `IndexedGraphScope.Package` 触发）专用投影路径，
 * 把同一个架构索引呈现为更聚焦于包结构的视图。
 *
 * @param viewportPolicy 默认的可见规模上限
 * @param displayLayerResolver 用于把节点归类到展示分层的解析器
 * @param hiddenBucketProjector 把被裁剪掉的节点按桶汇总的投影器
 */
class ArchitectureGraphProjector(
    private val viewportPolicy: GraphViewportPolicy = GraphViewportPolicy(),
    private val displayLayerResolver: ArchitectureDisplayLayerResolver = ArchitectureDisplayLayerResolver(),
    private val hiddenBucketProjector: GraphHiddenBucketProjector = GraphHiddenBucketProjector(),
) : GraphProjector {
    /**
     * 投影架构索引为架构图视图结果。
     *
     * 当请求范围是包（[IndexedGraphScope.Package]）时切换到包视图投影；
     * 否则按服务/组件/资源等结构节点执行通用投影流程。
     *
     * @param index 已构建好的架构索引
     * @param request 索引视图请求，决定范围、外部依赖是否纳入、视口参数等
     * @param cacheState 当前缓存状态的对外描述（用于摘要展示）
     * @param freshness 索引新鲜度信息
     * @return 完整的架构图投影结果
     */
    fun project(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest = requestArchitectureGraphRequest(),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ArchitectureGraphResult {
        if (request.scope is IndexedGraphScope.Package) {
            return projectPackageGraph(index, request, cacheState, freshness)
        }
        // 本次请求允许参与结构视图的节点种类（服务、组件、资源，按需扩展库/JDK）。
        val structureKinds = request.projectStructureKinds()
        // 在结构视图下应当出现的候选节点列表。
        val structureNodes = index.graph.nodes.filter { node ->
            node.kind in structureKinds && node.isVisibleProjectStructureNode(index)
        }
        // 被识别为"辅助/支撑"性质的节点（例如 demo、test 包中的组件）。
        val supportNodeIds = supportProjectStructureNodeIds(index)
        // 至少被一条 OVERVIEW 聚合关系覆盖的节点，用于判断孤立的"清单型"节点。
        val relationBackedNodeIds = relationBackedProjectStructureNodeIds(index)
        // 节点展示上下文（去重后的展示名、可读基名等）。
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
        // 视口裁剪与隐藏桶统计可能各自报告一部分隐藏数，取较大者作为最终展示。
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(visibleWindow.hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(visibleWindow.hiddenEdgeCount)
        return ArchitectureGraphResult(
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

    /**
     * 包视图投影：把架构索引转换为以包为单位的视图。
     *
     * 包视图仅保留包、资源、外部库与 JDK 这几类节点；当请求指定了具体包范围时，
     * 视图会聚焦到该包及其直接相关的边；否则仅保留至少参与一条包级关系的节点。
     * 剩余未被任何关系覆盖的"清单型"节点会被单独统计，用于摘要展示。
     *
     * @param index 架构索引
     * @param request 索引请求，携带范围与视口参数
     * @param cacheState 缓存状态描述
     * @param freshness 索引新鲜度
     * @return 包视图的架构图投影结果
     */
    private fun projectPackageGraph(
        index: ArchitectureGraphIndex,
        request: IndexedGraphRequest,
        cacheState: String,
        freshness: IndexedGraphFreshness,
    ): ArchitectureGraphResult {
        // 包视图允许出现的节点种类。
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
        // 所有参与包级关系的节点 ID 集合（默认情况下作为可见集合）。
        val relationshipNodeIds = index.graph.edges
            .asSequence()
            .filter { edge -> edge.metadata["architecture.aggregate.level"] == "PACKAGE" }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .toSet()
        // 当请求指定了具体包时，scope 范围内的所有包节点 ID。
        val scopedPackage = (request.scope as? IndexedGraphScope.Package)?.qualifiedName?.takeIf(String::isNotBlank)
        val scopedNodeIds = scopedPackage
            ?.let { packageName ->
                packageNodes
                    .filter { node -> node.qualifiedName == packageName || node.qualifiedName.startsWith("$packageName.") }
                    .mapTo(linkedSetOf(), ArchitectureNode::id)
            }
            .orEmpty()
        // 最终需要纳入完整图的节点 ID：聚焦范围时为"范围内 + 与之直接相关的"，
        // 否则使用全部参与包级关系的节点。
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
        // 至少参与一条关系的节点，会进入正式展示图。
        val relationshipNodes = candidateNodes.filter { node -> node.id in fullGraphNodeIds }
        // 完全孤立、仅作清单展示的节点，仅参与统计不进入图。
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
        // 仅保留包级别的边，剔除类级别细枝末节。
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
        return ArchitectureGraphResult(
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

    /**
     * 把架构节点集合投影为通用图文档。
     *
     * - 节点：通过 [ArchitectureNode.toGraphNode] 转换并附带展示元数据；
     * - 边：按 [includeClassEdges] 决定是否保留类级边，否则仅保留 OVERVIEW 聚合关系
     *   与少量结构包含/SPI 关系；
     * - 元数据：合并视图模式、关系种类/置信度/来源/计数、显示关系元数据、
     *   以及索引层级的统一元数据。
     *
     * @param index 架构索引
     * @param nodes 要参与投影的架构节点集合
     * @param includeClassEdges 是否保留类级别的边
     * @param viewMode 当前分析显示模式
     * @param request 索引请求，用于推导范围相关元数据
     * @param displayContexts 各节点的展示上下文
     * @param supportNodeIds 辅助性质节点 ID 集合
     * @param relationBackedNodeIds 至少被一条聚合关系覆盖的节点 ID 集合
     * @return 已排序的通用图文档
     */
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
        // 当前批次的节点 ID 集合，用于边过滤。
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

    /**
     * 结构视图专用图文档投影：在 [graphDocument] 基础上剔除噪声关系。
     *
     * 结构视图（项目结构）仅保留 OVERVIEW 级别的关系，
     * 同时排除过于底层的关系种类（如模块包含包、SPI 提供等），
     * 让最终展示更聚焦于组件之间的依赖。
     */
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

    /**
     * 把完整图中 OVERVIEW 级别的边聚合为"项目结构关系分组"。
     *
     * 每个分组由 `(from, to, displayRelationKind)` 三元组唯一标识，
     * 分组内的所有边被视为同一类关系的多条具体实例，参与汇总：
     * - 累计关系条数；
     * - 收集所有底层关系 ID 作为证据；
     * - 默认是否在当前可见图中展示。
     *
     * 最终按"默认可见优先、计数大优先、关系名稳定"排序。
     *
     * @receiver 完整图（包含全部 OVERVIEW 边）
     * @param visibleGraph 当前可见图，用于判断分组默认是否可见
     * @return 排序后的关系分组列表
     */
    private fun GraphDocument.projectStructureRelationGroups(visibleGraph: GraphDocument): List<ProjectStructureRelationGroup> {
        // 当前可见图中的边 ID 集合，用于判断分组默认可见性。
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
                // 当前分组下所有底层关系 ID 的并集，作为证据链。
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

    /**
     * 为图文档生成只读的投影索引。
     *
     * 架构图本身不可编辑，因此所有节点/边都被标记为 [GraphProjectionMappingKind.INDEXED_READONLY]，
     * 且不携带任何可执行的编辑命令种类。投影索引供上层把投影 ID 与规范化 ID 对应起来。
     *
     * @param graph 已投影的图文档
     * @return 只读投影索引
     */
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

    /**
     * 推导请求对应的标准架构视口策略。
     *
     * 优先使用请求自带的视口上限，缺失时回退到注入的默认策略。
     */
    private fun IndexedGraphRequest.architectureViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: viewportPolicy.maxVisibleNodes,
            maxVisibleEdges = viewport.maxVisibleEdges ?: viewportPolicy.maxVisibleEdges,
            enableOverflowSummary = viewportPolicy.enableOverflowSummary,
        )

    /**
     * 推导请求对应的结构视图专用视口策略。
     *
     * 结构视图通常希望节点更少，便于阅读，因此默认上限低于普通架构图。
     */
    private fun IndexedGraphRequest.architectureStructureViewportPolicy(): GraphViewportPolicy =
        GraphViewportPolicy(
            maxVisibleNodes = viewport.maxVisibleNodes ?: DEFAULT_STRUCTURE_VISIBLE_NODES,
            maxVisibleEdges = viewport.maxVisibleEdges ?: DEFAULT_STRUCTURE_VISIBLE_EDGES,
            enableOverflowSummary = viewportPolicy.enableOverflowSummary,
        )

    /**
     * 把架构节点转换为通用图节点。
     *
     * 携带：节点 ID、对应类型、标题、位置、可签名信息（类型节点才有）、文档、
     * 资源种类、绑定状态、确定性，以及大量元数据（视图模式、架构元信息、来源、
     * 展示分层、索引统一元数据等）。
     */
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
                putSourceLocation(
                    GraphSourceLocation(
                        filePath = source?.displayPath,
                        virtualFileUrl = source?.virtualFileUrl,
                        startLine = source?.startLine,
                        endLine = source?.endLine,
                        origin = index.symbolIndex.symbolsById[id]?.origin?.name,
                        decompiled = source?.decompiled ?: false,
                    ),
                )
                putAll(structureDisplayMetadata(index, displayLayer, displayContext, id in supportNodeIds, id in relationBackedNodeIds))
                putAll(architectureSourceSampleMetadata(index))
                putAll(indexedNodeMetadata(index, request.scopeKind()))
                putAll(displayLayer.presentationMetadata())
            },
        )
    }

    /**
     * 构建结构视图下节点附带的展示元数据。
     *
     * 包括：展示名、子标题、可读基名（如有）、展示分层与角色、
     * 是否过于宽泛（聚合粒度过大）、是否属于辅助/支撑节点、是否被关系覆盖、
     * 排序权重，以及边界种类。
     */
    private fun ArchitectureNode.structureDisplayMetadata(
        index: ArchitectureGraphIndex,
        displayLayer: ArchitectureDisplayLayer,
        displayContext: StructureDisplayContext?,
        supportNode: Boolean,
        relationBackedNode: Boolean,
    ): Map<String, String> {
        // 当前节点是否因为聚合粒度过大而需要在 UI 上降权/隐藏。
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

    /**
     * 节点的可读结构名：资源节点直接使用 title，
     * 其余类型在 title 为空时回退到全限定名的最后一段。
     */
    private fun ArchitectureNode.readableStructureName(): String {
        if (kind == ArchitectureNodeKind.RESOURCE) {
            return title
        }
        return title.ifBlank { qualifiedName.substringAfterLast('.') }
    }

    /**
     * 构造节点在结构视图中的子标题，区分资源、服务、组件、外部依赖与 JDK。
     */
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

    /**
     * 计算节点在结构视图中的排序权重。
     *
     * 数值越小越靠前。综合考虑：聚合是否过宽、是否辅助节点、是否孤立、
     * 分层权重、成员角色权重、名称权重以及"更小的成员数加分"。
     */
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

    /**
     * 根据成员类中最高优先级的 Stereotype 推断角色权重。
     *
     * Controller 优先级最高，依次为 Service、Repository、Configuration，
     * 都不匹配时返回最大值表示未知。
     */
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

    /**
     * 收集所有被识别为辅助性质（demo/test/mock 等）的组件/服务节点 ID。
     */
    private fun supportProjectStructureNodeIds(index: ArchitectureGraphIndex): Set<String> =
        index.graph.nodes
            .asSequence()
            .filter { node -> node.kind in setOf(ArchitectureNodeKind.COMPONENT, ArchitectureNodeKind.SERVICE) }
            .filter { node -> node.isSupportProjectStructureNode(index) }
            .map(ArchitectureNode::id)
            .toSet()

    /**
     * 收集所有至少参与一条 OVERVIEW 聚合关系（且非噪声种类）的节点 ID。
     */
    private fun relationBackedProjectStructureNodeIds(index: ArchitectureGraphIndex): Set<String> =
        index.graph.edges
            .asSequence()
            .filter { edge ->
                edge.metadata["architecture.aggregate.level"] == "OVERVIEW" &&
                    edge.metadata["jvm.relation.kind"] !in hiddenProjectStructureRelationKinds
            }
            .flatMap { edge -> sequenceOf(edge.fromNodeId, edge.toNodeId) }
            .toSet()

    /**
     * 判定组件/服务节点是否属于辅助性质。
     *
     * 判定规则：包路径中含 demo/test/mock/benchmark 等关键字，
     * 或所有成员类都是测试源/位于测试源路径下。
     */
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

    /**
     * 判定源代码路径是否位于测试/示例目录下。
     */
    private fun String.hasSupportSourcePath(): Boolean {
        val segments = replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
            .map(String::lowercase)
        return segments.any { segment -> segment in supportSourcePathSegments }
    }

    /**
     * 为一组节点计算展示上下文（用于避免展示名冲突）。
     *
     * 流程：先计算每个节点的"可读基名"，统计出现冲突的基名；
     * 冲突的节点回退到最短唯一后缀，作为展示名。
     */
    private fun List<ArchitectureNode>.structureDisplayContexts(): Map<String, StructureDisplayContext> {
        val readableBaseNames = associate { node -> node.id to node.readableStructureBaseName(this) }
        // 出现次数大于 1 的基名集合，用于回退到更长的唯一名称。
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

    /**
     * 计算节点的可读基名：去掉公共根前缀和项目组织前缀后的剩余命名。
     */
    private fun ArchitectureNode.readableStructureBaseName(allNodes: List<ArchitectureNode>): String {
        if (kind == ArchitectureNodeKind.RESOURCE) {
            return title
        }
        val parts = qualifiedName.split('.').filter(String::isNotBlank)
        if (parts.isEmpty()) {
            return title.ifBlank { qualifiedName }
        }
        // 全部节点的全限定名分段，用于计算公共根。
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

    /**
     * 在分段列表中再剥除模块名/组织前缀，得到更短的展示用命名片段。
     */
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

    /**
     * 计算节点在全节点集合中最短且唯一的名称后缀。
     *
     * 从最短 2 段后缀开始尝试，遇到不冲突的后缀即返回，确保展示名既短又唯一。
     */
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

    /**
     * 计算多组命名分段列表的公共前缀长度。
     */
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

    /**
     * 判定组件/服务聚合是否过于宽泛（命名层级过浅或成员类占比过高）。
     *
     * 宽泛的聚合会被 UI 降权或隐藏，避免出现"项目根聚合"这类无意义节点。
     */
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
        // 项目自身源码类的总数，作为成员数比较的分母。
        val projectClassCount = index.symbolIndex.classesByQualifiedName.values.count { cls ->
            !cls.external && !cls.library && !cls.jdk && !cls.testSource
        }.coerceAtLeast(1)
        if (memberClassIds.size > projectClassCount * BROAD_STRUCTURE_NODE_RATIO) {
            return true
        }
        return false
    }

    /**
     * 构造架构视图的整体呈现层信息：目标节点、泳道、隐藏桶、控件。
     *
     * @param visibleGraph 当前可见图
     * @param fullGraph 完整图
     * @param anchorNodeId 锚点节点 ID（用于目标展示）
     * @return 可直接渲染的呈现层信息
     */
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

    /**
     * 给尚未填充展示元数据的节点补齐默认展示信息。
     *
     * 节点若已有 `presentation.role` 则保留原值，否则按解析出的分层注入。
     */
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

    /**
     * 把展示分层转换为前端可识别的展示元数据键值对。
     */
    private fun ArchitectureDisplayLayer.presentationMetadata(): Map<String, String> =
        mapOf(
            "presentation.role" to role,
            "presentation.laneId" to laneId,
            "presentation.priority" to order.toString(),
            "presentation.compact" to "true",
        )

    /**
     * 把隐藏桶的 ID 转换为中文展示标签。
     */
    private fun architectureBucketLabel(bucket: String): String =
        ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == bucket }?.label ?: bucket

    /**
     * 构造节点的源代码示例元数据：数量、首要示例（用于导航）以及全部样本明细。
     */
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

    /**
     * 收集节点的源代码样本：节点自身来源、成员类来源与成员资源来源。
     *
     * 同一节点 ID 只保留第一个样本，结果按路径与起始行排序，
     * 总数不超过 [MAX_ARCHITECTURE_SOURCE_SAMPLES]。
     */
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

    /**
     * 节点默认文档说明：根据节点种类生成成员数量描述。
     */
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

    /**
     * 把架构节点种类映射到通用图节点类型。
     *
     * 注意：外部库与 JDK 在通用类型系统中合并为 [NodeType.LIBRARY]。
     */
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

    /**
     * 判定节点种类是否属于"类型节点"（可使用全限定名作为签名）。
     */
    private fun ArchitectureNodeKind.isTypeLike(): Boolean =
        this in setOf(
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
        )

    /**
     * 节点优先级：数字越小越优先保留。
     *
     * 优先使用预计算的 `architecture.structureRank`，缺失时按节点类型回退。
     */
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

    /**
     * 边优先级：数字越小越优先保留。
     *
     * 结构边最优先，其次按聚合层级，再按 JVM 关系种类排序。
     */
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

    /**
     * 把 JVM 关系种类映射到展示层"显示关系种类"和中文标签。
     *
     * 例如：调用、注入、路由等归为运行时调用；继承、实现归为类型依赖。
     */
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

    /**
     * 从可见图中挑选架构锚点节点。
     *
     * 选择优先级：1) 优先角色（API/ENTRY/SERVICE 等）且非宽聚合且有源样本；
     * 2) 含源样本的聚合类型节点；3) 同样角色但允许无源样本；
     * 4) 任意 MODULE 节点；5) 第一个节点。
     */
    private fun selectArchitectureAnchorNodeId(graph: GraphDocument): String? {
        // 角色优先级列表，越靠前越优先。
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

    /**
     * 判定节点是否携带至少一个源代码样本。
     */
    private fun GraphNode.hasArchitectureSourceSamples(): Boolean =
        metadata["architecture.sourceSample.count"]?.toIntOrNull()?.let { count -> count > 0 } == true

    /**
     * 判定图节点是否对应一个过于宽泛的架构聚合。
     *
     * 仅 MODULE 节点或角色未知且命名空间过宽的 COMPONENT 节点被视为宽聚合。
     */
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

    /**
     * 判定组件命名空间是否过宽：是否有较多更细粒度的子节点位于其下。
     */
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

    /**
     * 判定节点是否应当出现在结构视图的可见集合中。
     *
     * 资源节点需要排除构建产物、缓存目录等噪声路径。
     */
    private fun ArchitectureNode.isVisibleProjectStructureNode(index: ArchitectureGraphIndex): Boolean {
        if (kind != ArchitectureNodeKind.RESOURCE) {
            return true
        }
        val paths = resourcePaths(index)
        return paths.isEmpty() || paths.any { path -> !path.hasExcludedResourcePathSegment() }
    }

    /**
     * 收集节点的所有资源路径：自身元数据中的路径 + 成员资源路径。
     */
    private fun ArchitectureNode.resourcePaths(index: ArchitectureGraphIndex): List<String> =
        buildList {
            metadata["resource.path"]?.let(::add)
            memberResourceIds.mapNotNullTo(this) { resourceId ->
                (index.symbolIndex.findSymbol(resourceId) as? JvmResourceSymbol)?.path
            }
        }

    /**
     * 判定资源路径是否包含应当排除的目录段。
     *
     * 例如 `.git`、`node_modules` 始终排除；`build`、`dist` 这类生成目录
     * 在出现在 `src` 之前时才视为生成产物排除。
     */
    private fun String.hasExcludedResourcePathSegment(): Boolean {
        val segments = replace('\\', '/')
            .split('/')
            .filter(String::isNotBlank)
        return segments.withIndex().any { (index, segment) ->
            segment in alwaysExcludedResourcePathSegments ||
                segment in generatedResourcePathSegments && "src" !in segments.take(index)
        }
    }

    /**
     * 根据请求参数推导结构视图下需要保留的架构节点种类集合。
     */
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

    /**
     * 根据请求参数推导视口裁剪时使用的种子节点类型集合。
     */
    private fun IndexedGraphRequest.projectSeedNodeTypes(): Set<NodeType> =
        buildSet {
            add(NodeType.SERVICE)
            add(NodeType.COMPONENT)
            add(NodeType.RESOURCE)
            if (includeExternalLibraries || includeJdk) {
                add(NodeType.LIBRARY)
            }
        }

    /**
     * 单个源代码样本：携带节点 ID、来源引用以及采集原因。
     */
    private data class ArchitectureSourceSample(
        val nodeId: String,
        val source: JvmSourceRef,
        val reason: String,
    )

    /**
     * 节点展示上下文：展示名与可读基名，用于避免重名并支持 UI 渲染。
     */
    internal data class StructureDisplayContext(
        val displayName: String,
        val readableBaseName: String?,
    )

    private companion object {
        /** 单个节点最多保留的源样本数量。 */
        private const val MAX_ARCHITECTURE_SOURCE_SAMPLES = 8
        /** 结构视图默认最大可见节点数。 */
        private const val DEFAULT_STRUCTURE_VISIBLE_NODES = 12
        /** 结构视图默认最大可见边数。 */
        private const val DEFAULT_STRUCTURE_VISIBLE_EDGES = 18
        /** 判定聚合节点是否过宽的成员类占比阈值。 */
        private const val BROAD_STRUCTURE_NODE_RATIO = 0.55
        /** 在结构视图中需要被剔除的关系种类（结构包含/SPI 等噪声关系）。 */
        private val hiddenProjectStructureRelationKinds = setOf(
            JvmRelationKind.MODULE_CONTAINS_PACKAGE.name,
            JvmRelationKind.PACKAGE_CONTAINS_CLASS.name,
            JvmRelationKind.SPI_PROVIDES.name,
            JvmRelationKind.SERVICE_LOADER_LOADS.name,
        )
        /** 资源路径中应当无条件排除的目录段。 */
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
        /** 资源路径中的生成产物目录段，按上下文判断是否排除。 */
        private val generatedResourcePathSegments = setOf(
            "build",
            "coverage",
            "dist",
            "out",
            "target",
            "temp",
            "tmp",
        )
        /** 常见组织前缀段，用于展示时剥除命名空间前缀。 */
        private val organizationPrefixSegments = setOf(
            "com",
            "org",
            "net",
            "io",
            "dev",
        )
        /** 辅助/测试性质的包名段，命中即视为非项目主体代码。 */
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
        /** 辅助/测试性质的源码路径段，命中即视为非项目主体代码。 */
        private val supportSourcePathSegments = supportPackageSegments + setOf(
            "src/test",
            "src/integrationtest",
            "src/integration-test",
        )
    }
}

/**
 * 把 JVM 关系种类映射到通用边类型，用于把索引关系转换为图文档边。
 */
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

/**
 * 把 JVM 关系置信度映射到通用确定性枚举。
 */
internal fun JvmRelationConfidence.toCertainty(): Certainty =
    when (this) {
        JvmRelationConfidence.PROVEN -> Certainty.PROVEN
        JvmRelationConfidence.RULE_INFERRED,
        JvmRelationConfidence.AMBIGUOUS,
        JvmRelationConfidence.RUNTIME_REQUIRED,
        -> Certainty.RULE_INFERRED
    }

/**
 * 根据 JVM 关系种类返回简洁的英文展示标签。
 */
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
