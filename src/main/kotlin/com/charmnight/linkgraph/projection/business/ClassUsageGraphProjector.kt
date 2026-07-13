package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.architecture.ClassDiagramSummary
import com.charmnight.linkgraph.application.indexed.IndexedGraphLayerCounts
import com.charmnight.linkgraph.application.indexed.IndexedGraphSummary
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.usage.ClassUsageEntry
import com.charmnight.linkgraph.usage.ClassUsageGroup
import com.charmnight.linkgraph.usage.ClassUsageOwnerKind
import com.charmnight.linkgraph.usage.ClassUsageSearchResult
import com.charmnight.linkgraph.usage.ClassUsageTarget
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.model.GraphBinding
import com.charmnight.linkgraph.model.GraphConfidence
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceLocation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.putSourceLocation
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.projection.graphProjectionHiddenNodes

/**
 * 类用法图投影器。
 *
 * 把"某个类被哪些地方使用"的搜索结果叠加到既有的类图视图上，
 * 生成展示用法调用方的图文档。也可以在没有任何基础类图时通过
 * [projectStandalone] 直接生成一个仅包含目标类的最小视图。
 */
class ClassUsageGraphProjector : GraphProjector {
    /**
     * 在没有基础类图的情况下，仅根据用法搜索结果生成一个最小类图视图。
     *
     * 视图只包含目标类节点和调用方节点，常用于快速查看类用法。
     */
    fun projectStandalone(usageResult: ClassUsageSearchResult): ClassDiagramResult =
        project(standaloneBaseView(usageResult), usageResult)

    /**
     * 把类用法结果叠加到基础类图视图上。
     *
     * @param baseView 既有的类图视图，作为叠加基础
     * @param usageResult 类用法搜索结果；为空时直接返回基础视图
     * @return 叠加调用方节点与用法边后的新视图
     */
    fun project(
        baseView: ClassDiagramResult,
        usageResult: ClassUsageSearchResult?,
    ): ClassDiagramResult {
        usageResult ?: return baseView
        val targetNodeId = usageResult.target.nodeId
        val ownerNodes = usageResult.groups.map(::ownerNode)
        val usageEdges = usageResult.groups.mapNotNull { group -> usageEdge(group, targetNodeId) }
        val visibleGraph = baseView.visibleGraph.withUsageOverlay(ownerNodes, usageEdges)
        val fullGraph = baseView.fullGraph.withUsageOverlay(ownerNodes, usageEdges)
        val projectionIndex = baseView.projectionIndex.withUsageOverlayMappings(visibleGraph, fullGraph)
        return baseView.copy(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = baseView.anchorNodeId ?: targetNodeId,
            summary = baseView.summary.withUsageGraphCounts(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                usageResult = usageResult,
            ),
            projectionIndex = projectionIndex,
            usage = usageResult,
        )
    }

    /**
     * 构造仅含目标类的最小基础视图，用于 [projectStandalone] 流程。
     *
     * 该视图自带完整的摘要与索引描述，但不携带任何关系。
     */
    private fun standaloneBaseView(usageResult: ClassUsageSearchResult): ClassDiagramResult {
        val targetNode = targetNode(usageResult.target)
        val graph = GraphDocument(nodes = listOf(targetNode))
        return ClassDiagramResult(
            visibleGraph = graph,
            fullGraph = graph,
            anchorNodeId = usageResult.target.nodeId,
            summary = ClassDiagramSummary(
                classCount = 1,
                relationCompleteness = "STRUCTURE_ONLY",
                scopeTypeCount = 1,
                projectTypeCount = 1,
                projectClassCount = 1,
                anchorTypeNodeId = usageResult.target.nodeId,
                anchorTypeTitle = usageResult.target.displayName,
                anchorTypeQualifiedName = usageResult.target.qualifiedName,
                indexed = standaloneIndexedSummary(usageResult.target),
            ),
            projectionIndex = GraphProjectionIndex.EMPTY,
        )
    }

    /**
     * 构造最小视图对应的索引摘要，描述这是一个仅含目标类的"结构仅"视图。
     */
    private fun standaloneIndexedSummary(target: ClassUsageTarget): IndexedGraphSummary {
        // 单一项目源类层级计数。
        val projectSourceLayer = IndexedGraphLayerCounts(projectSource = 1)
        return IndexedGraphSummary(
            view = "CLASS_DIAGRAM",
            anchorKind = "CLASS",
            anchorNodeId = target.nodeId,
            anchorTitle = target.displayName,
            anchorQualifiedName = target.qualifiedName,
            scopeKind = "CLASS_USAGE",
            scopeLabel = target.qualifiedName,
            depth = 0,
            projectNodeCount = 1,
            projectClassCount = 1,
            externalNodeCount = 0,
            jdkNodeCount = 0,
            scopedNodeCount = 1,
            visibleNodeCount = 1,
            hiddenNodeCount = 0,
            hiddenEdgeCount = 0,
            candidateNodeCount = 1,
            candidateEdgeCount = 0,
            truncated = false,
            completeness = "STRUCTURE_ONLY",
            cacheState = "STANDALONE_USAGE",
            includeExternalLibraries = false,
            includeJdk = false,
            projectSourceNodeCount = 1,
            projectLayerCounts = projectSourceLayer,
            visibleLayerCounts = projectSourceLayer,
            scopedLayerCounts = projectSourceLayer,
            candidateLayerCounts = projectSourceLayer,
        )
    }

    /**
     * 把用法搜索目标转换为图节点，并附带大量预置元数据，
     * 让前端能够直接渲染锚点节点（即使在没有详细类信息的情况下）。
     */
    private fun targetNode(target: ClassUsageTarget): GraphNode {
        val packageName = target.qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
        val metadata = linkedMapOf(
            "architecture.node.kind" to "CLASS",
            "architecture.qualifiedName" to target.qualifiedName,
            "class.qualifiedName" to target.qualifiedName,
            "jvm.class.kind" to JvmClassKind.CLASS.name,
            "jvm.stereotype" to "UNKNOWN",
            "presentation.role" to "ANCHOR",
            "presentation.laneId" to "anchor",
            "presentation.compact" to "false",
            "layout.direction" to "ANCHOR",
            "uml.field.count" to "0",
            "uml.method.count" to "0",
            "uml.field.hiddenCount" to "0",
            "uml.method.hiddenCount" to "0",
            "indexed.layerKind" to "PROJECT_SOURCE",
            "indexed.nodeRole" to "UNKNOWN",
            "indexed.scopeKind" to "CLASS_NEIGHBORHOOD",
            "indexed.sourceKind" to "SOURCE_CLASS",
            "indexed.memberClassCount" to "0",
            "indexed.memberResourceCount" to "0",
            GraphProjectionMetadata.Indexed.COLLAPSED_COUNT to "0",
            "indexed.expandable" to "false",
            "indexed.member.projectSource" to "0",
            "indexed.member.externalLibrary" to "0",
            "indexed.member.jdk" to "0",
            "indexed.member.resource" to "0",
            "indexed.member.aggregate" to "0",
            GraphProjectionMetadata.Indexed.Collapsed.PROJECT_SOURCE to "0",
            GraphProjectionMetadata.Indexed.Collapsed.EXTERNAL_LIBRARY to "0",
            GraphProjectionMetadata.Indexed.Collapsed.JDK to "0",
            GraphProjectionMetadata.Indexed.Collapsed.RESOURCE to "0",
            GraphProjectionMetadata.Indexed.Collapsed.AGGREGATE to "0",
        )
        if (packageName.isNotBlank()) {
            metadata["architecture.package"] = packageName
            metadata["class.package"] = packageName
        }
        return GraphNode(
            id = target.nodeId,
            type = NodeType.CLASS,
            title = target.displayName,
            signature = target.qualifiedName,
            binding = GraphBinding.CODE_BOUND,
            confidence = GraphConfidence.VERIFIED,
            metadata = metadata,
        )
    }

    /**
     * 把用法分组（一个调用方对应的所有用法集合）转换为图节点。
     *
     * 节点元数据携带分组的用法数量、种类、展示与索引元数据，
     * 用于把每个调用方呈现为一个"使用方"节点。
     */
    private fun ownerNode(group: ClassUsageGroup): GraphNode {
        val metadata = linkedMapOf(
            "classUsage.groupId" to group.id,
            "classUsage.ownerKind" to group.ownerKind.name,
            "classUsage.count" to group.usages.size.toString(),
            "classUsage.kinds" to group.usages.map { usage -> usage.kind.name }.distinct().joinToString(","),
            "presentation.role" to "CALLER",
            "presentation.laneId" to "caller",
            "presentation.compact" to "true",
            "layout.direction" to "INCOMING",
            "uml.field.count" to "0",
            "uml.method.count" to "0",
            "uml.field.hiddenCount" to "0",
            "uml.method.hiddenCount" to "0",
            "indexed.layerKind" to if (group.ownerKind == ClassUsageOwnerKind.FILE) "RESOURCE" else "PROJECT_SOURCE",
            "indexed.nodeRole" to "UNKNOWN",
            "indexed.scopeKind" to "CLASS_NEIGHBORHOOD",
            "indexed.sourceKind" to if (group.ownerKind == ClassUsageOwnerKind.FILE) "RESOURCE_FILE" else "SOURCE_CLASS",
            "indexed.memberClassCount" to "0",
            "indexed.memberResourceCount" to "0",
            GraphProjectionMetadata.Indexed.COLLAPSED_COUNT to "0",
            "indexed.expandable" to "false",
            "indexed.member.projectSource" to "0",
            "indexed.member.externalLibrary" to "0",
            "indexed.member.jdk" to "0",
            "indexed.member.resource" to "0",
            "indexed.member.aggregate" to "0",
            GraphProjectionMetadata.Indexed.Collapsed.PROJECT_SOURCE to "0",
            GraphProjectionMetadata.Indexed.Collapsed.EXTERNAL_LIBRARY to "0",
            GraphProjectionMetadata.Indexed.Collapsed.JDK to "0",
            GraphProjectionMetadata.Indexed.Collapsed.RESOURCE to "0",
            GraphProjectionMetadata.Indexed.Collapsed.AGGREGATE to "0",
        )
        if (group.ownerKind == ClassUsageOwnerKind.CLASS) {
            metadata["architecture.node.kind"] = "CLASS"
            metadata["jvm.class.kind"] = JvmClassKind.CLASS.name
            metadata["jvm.stereotype"] = "UNKNOWN"
        }
        group.qualifiedName?.let {
            metadata["architecture.qualifiedName"] = it
            metadata["class.qualifiedName"] = it
        }
        group.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf(String::isNotBlank)
            ?.let {
                metadata["architecture.package"] = it
                metadata["class.package"] = it
            }
        metadata.putSourceLocation(
            GraphSourceLocation(
                filePath = group.filePath,
                virtualFileUrl = group.virtualFileUrl,
            ),
        )
        return GraphNode(
            id = group.ownerNodeId ?: group.id,
            type = group.ownerKind.toNodeType(),
            title = group.title,
            location = group.filePath,
            signature = group.qualifiedName,
            doc = "使用 ${group.usages.size} 处",
            binding = GraphBinding.CODE_BOUND,
            confidence = GraphConfidence.VERIFIED,
            metadata = metadata,
        )
    }

    /**
     * 把用法分组转换为指向目标类的使用边。
     *
     * 自引用分组（owner 即目标本身）返回空，避免出现自环。
     * 边携带用法次数、种类、索引元数据与样本 ID，便于前端呈现与统计。
     */
    private fun usageEdge(
        group: ClassUsageGroup,
        targetNodeId: String,
    ): GraphEdge? {
        val sourceNodeId = group.ownerNodeId ?: group.id
        if (sourceNodeId == targetNodeId) {
            return null
        }
        return GraphEdge(
            id = "class-usage:${sourceNodeId}->${targetNodeId}",
            type = EdgeType.USES_TYPE,
            fromNodeId = sourceNodeId,
            toNodeId = targetNodeId,
            label = "使用",
            confidence = GraphConfidence.VERIFIED,
            binding = GraphBinding.CODE_BOUND,
            metadata = mapOf(
                "classDiagram.relation.role" to "CLASS_USAGE",
                "classDiagram.relation.label" to "usage",
                "classDiagram.relation.roleLabel" to "usage",
                "classDiagram.relation.weight" to "18",
                "jvm.relation.kind" to "USES_TYPE",
                "jvm.relation.confidence" to "PROVEN",
                "jvm.relation.count" to group.usages.size.toString(),
                "indexed.relationKind" to "CLASS_USAGE",
                "indexed.relationLayer" to "PROJECT_INTERNAL",
                "indexed.sourceCount" to group.usages.size.toString(),
                "indexed.sampleCount" to group.usages.size.coerceAtMost(3).toString(),
                "indexed.sourceRelationIds" to group.usages.joinToString(",") { usage -> usage.id },
                "architecture.sourceRelationIds" to group.usages.joinToString(",") { usage -> usage.id },
                "classUsage.groupId" to group.id,
                "classUsage.count" to group.usages.size.toString(),
                "classUsage.kinds" to group.usages.map { usage -> usage.kind.name }.distinct().joinToString(","),
                "layout.labelPlacement" to "target-stub",
            ),
        )
    }

    /**
     * 把用法节点和边叠加到既有图文档上。
     *
     * 同 ID 的节点会与现有节点合并，边则按 ID 直接覆盖。
     */
    private fun GraphDocument.withUsageOverlay(
        ownerNodes: List<GraphNode>,
        usageEdges: List<GraphEdge>,
    ): GraphDocument {
        val nodeById = linkedMapOf<String, GraphNode>()
        nodes.forEach { nodeById[it.id] = it }
        ownerNodes.forEach { ownerNode ->
            nodeById[ownerNode.id] = nodeById[ownerNode.id]?.mergeUsageOverlay(ownerNode) ?: ownerNode
        }
        val edgeById = linkedMapOf<String, GraphEdge>()
        edges.forEach { edgeById[it.id] = it }
        usageEdges.forEach { edgeById[it.id] = it }
        return copy(nodes = nodeById.values.toList(), edges = edgeById.values.toList())
    }

    /**
     * 把用法叠加节点合并到既有节点上：覆盖文档，并合并与用法/展示/布局相关的元数据。
     */
    private fun GraphNode.mergeUsageOverlay(overlay: GraphNode): GraphNode =
        copy(
            doc = overlay.doc ?: doc,
            metadata = metadata + overlay.metadata.filterKeys { key ->
                key.startsWith("classUsage.") ||
                    key.startsWith("presentation.") ||
                    key.startsWith("layout.")
            },
        )

    /**
     * 在叠加用法节点后扩展投影索引映射。
     *
     * 已有映射保持不变，新增的节点和边按只读、单规范 ID 的方式注入映射。
     */
    private fun GraphProjectionIndex.withUsageOverlayMappings(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
    ): GraphProjectionIndex {
        val fullNodeIds = fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
        val nodeMappings = linkedMapOf<String, GraphProjectionNodeMapping>()
        visibleGraph.nodes.forEach { node ->
            nodeMappings[node.id] = this.nodeMappings[node.id]
                ?: GraphProjectionNodeMapping(
                    projectedNodeId = node.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalNodeIds = listOf(node.id).filter(fullNodeIds::contains),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
        }
        val edgeMappings = linkedMapOf<String, GraphProjectionEdgeMapping>()
        visibleGraph.edges.forEach { edge ->
            edgeMappings[edge.id] = this.edgeMappings[edge.id]
                ?: GraphProjectionEdgeMapping(
                    projectedEdgeId = edge.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalEdgeIds = listOf(edge.id).filter(fullEdgeIds::contains),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
        }
        return GraphProjectionIndex(
            nodeMappings = nodeMappings,
            edgeMappings = edgeMappings,
        )
    }

    /**
     * 用叠加后的图规模更新类图摘要计数。
     *
     * 包括按 `jvm.class.kind` 重新统计各类型节点数、字段总数、关系总数，
     * 并按隐藏计数更新截断标记与索引摘要。
     */
    private fun com.charmnight.linkgraph.architecture.ClassDiagramSummary.withUsageGraphCounts(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        usageResult: ClassUsageSearchResult,
    ): com.charmnight.linkgraph.architecture.ClassDiagramSummary {
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount.coerceAtLeast(hiddenNodeCount)
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount.coerceAtLeast(hiddenEdgeCount)
        return copy(
            classCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.CLASS.name },
            fieldCount = visibleGraph.nodes.sumOf { node -> node.metadata["uml.field.count"]?.toIntOrNull() ?: 0 },
            interfaceCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.INTERFACE.name },
            enumCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.ENUM.name },
            annotationCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.ANNOTATION.name },
            recordCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.RECORD.name },
            objectCount = visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.OBJECT.name },
            relationCount = visibleGraph.edges.size,
            scopeTypeCount = scopeTypeCount.coerceAtLeast(fullGraph.nodes.size).coerceAtLeast(visibleGraph.nodes.size),
            projectTypeCount = projectTypeCount.coerceAtLeast(fullGraph.nodes.size).coerceAtLeast(visibleGraph.nodes.size),
            projectClassCount = projectClassCount.coerceAtLeast(
                visibleGraph.nodes.count { node -> node.metadata["jvm.class.kind"] == JvmClassKind.CLASS.name },
            ),
            hiddenNodeCount = hiddenNodeCount,
            hiddenEdgeCount = hiddenEdgeCount,
            truncated = truncated || usageResult.summary.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
            indexed = indexed?.withUsageGraphCounts(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                truncated = truncated || usageResult.summary.truncated || hiddenNodeCount > 0 || hiddenEdgeCount > 0,
            ),
        )
    }

    /**
     * 用叠加后的图规模更新索引摘要计数，包含层级分布的重新统计。
     */
    private fun com.charmnight.linkgraph.application.indexed.IndexedGraphSummary.withUsageGraphCounts(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        hiddenNodeCount: Int,
        hiddenEdgeCount: Int,
        truncated: Boolean,
    ): com.charmnight.linkgraph.application.indexed.IndexedGraphSummary =
        copy(
            scopedNodeCount = scopedNodeCount.coerceAtLeast(fullGraph.nodes.size).coerceAtLeast(visibleGraph.nodes.size),
            visibleNodeCount = visibleGraph.nodes.size,
            hiddenNodeCount = hiddenNodeCount,
            hiddenEdgeCount = hiddenEdgeCount,
            candidateNodeCount = candidateNodeCount.coerceAtLeast(visibleGraph.nodes.size),
            candidateEdgeCount = candidateEdgeCount.coerceAtLeast(visibleGraph.edges.size).coerceAtLeast(fullGraph.edges.size),
            truncated = truncated,
            visibleLayerCounts = visibleGraph.nodes.usageIndexedLayerCounts(),
            scopedLayerCounts = fullGraph.nodes.usageIndexedLayerCounts(),
            candidateLayerCounts = candidateLayerCounts.maxWith(visibleGraph.nodes.usageIndexedLayerCounts()),
            hiddenLayerCounts = graphProjectionHiddenNodes(visibleGraph = visibleGraph, fullGraph = fullGraph)
                .usageIndexedLayerCounts(),
        )

    /**
     * 把节点集合按 `indexed.layerKind` 元数据归并成索引层级计数。
     */
    private fun Iterable<GraphNode>.usageIndexedLayerCounts(): IndexedGraphLayerCounts =
        fold(IndexedGraphLayerCounts()) { counts, node ->
            counts + when (node.metadata["indexed.layerKind"]) {
                "PROJECT_SOURCE" -> IndexedGraphLayerCounts(projectSource = 1)
                "EXTERNAL_LIBRARY" -> IndexedGraphLayerCounts(externalLibrary = 1)
                "JDK" -> IndexedGraphLayerCounts(jdk = 1)
                "RESOURCE" -> IndexedGraphLayerCounts(resource = 1)
                else -> IndexedGraphLayerCounts(aggregate = 1)
            }
        }

    /**
     * 取两个层级计数中每一层的最大值，用于候选层级数的合并展示。
     */
    private fun IndexedGraphLayerCounts.maxWith(other: IndexedGraphLayerCounts): IndexedGraphLayerCounts =
        IndexedGraphLayerCounts(
            projectSource = projectSource.coerceAtLeast(other.projectSource),
            externalLibrary = externalLibrary.coerceAtLeast(other.externalLibrary),
            jdk = jdk.coerceAtLeast(other.jdk),
            resource = resource.coerceAtLeast(other.resource),
            aggregate = aggregate.coerceAtLeast(other.aggregate),
        )

    /**
     * 把用法拥有者种类映射到通用节点类型：类、方法或资源（文件）。
     */
    private fun ClassUsageOwnerKind.toNodeType(): NodeType =
        when (this) {
            ClassUsageOwnerKind.CLASS -> NodeType.CLASS
            ClassUsageOwnerKind.METHOD -> NodeType.METHOD
            ClassUsageOwnerKind.FILE -> NodeType.RESOURCE
        }
}
