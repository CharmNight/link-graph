package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.sourceLocation

/**
 * 表示一次证据锚点解析的结果。
 * 既包含原始请求参数，也包含解析命中节点、解析后真实节点 ID 与解析过程中收集的映射轨迹。
 */
data class QaEvidenceAnchorResolution(
    /** 调用方传入的原始节点 ID。 */
    val requestedNodeId: String? = null,
    /** 调用方传入的原始符号签名。 */
    val requestedSymbolSignature: String? = null,
    /** 解析到的图节点；为空表示未命中可读节点。 */
    val node: GraphNode? = null,
    /** 解析后的真实节点 ID，缺失时回退到 node.id。 */
    val resolvedNodeId: String? = node?.id,
    /** 解析过程中按顺序收集的映射轨迹，便于排查为什么命中到当前节点。 */
    val mappingTrace: List<String> = emptyList(),
)

/**
 * 问答场景下证据锚点解析器。
 * 在节点 ID 或符号签名之外，还会通过投影索引、信任导航节点和多种图视图回退查找真实可读节点，
 * 同时记录完整映射轨迹，供 UI 展示和日志排查使用。
 */
class QaEvidenceAnchorResolver {
    /**
     * 解析证据锚点。
     *
     * @param snapshot 当前工具可访问的图快照。
     * @param nodeId 优先按节点 ID 查找。
     * @param symbolSignature 缺失节点 ID 时按签名查找。
     */
    fun resolve(
        snapshot: ToolGraphSnapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): QaEvidenceAnchorResolution {
        val normalizedNodeId = nodeId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedSymbol = symbolSignature?.trim()?.takeIf(String::isNotEmpty)
        val trace = mutableListOf<String>()
        var fallbackNode: GraphNode? = null

        fun rememberFallback(node: GraphNode?, stage: String) {
            if (node != null && fallbackNode == null) {
                fallbackNode = node
                trace += "$stage:${node.id}:no-source"
            }
        }

        fun readable(node: GraphNode?, stage: String): GraphNode? {
            if (node == null) {
                return null
            }
            if (hasSourceMetadata(node)) {
                trace += "$stage:${node.id}:${node.sourceLocation().filePath.orEmpty()}"
                return node
            }
            rememberFallback(node, stage)
            return null
        }

        val currentView = currentView(snapshot)
        if (normalizedNodeId != null) {
            readable(currentView.visibleGraph.findNode(normalizedNodeId), "currentGraph")?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }

            val mappedCanonicalIds = currentView.projectionIndex.nodeMapping(normalizedNodeId)
                ?.canonicalNodeIds
                .orEmpty()
                .filter { canonicalNodeId -> canonicalNodeId.isNotBlank() }
                .distinct()
            if (mappedCanonicalIds.isNotEmpty()) {
                trace += mappedCanonicalIds.joinToString(
                    prefix = "projectionIndex:$normalizedNodeId->",
                    separator = ",",
                )
                mappedCanonicalIds.firstReadableFrom(
                    documents = listOf(
                        "canonicalGraph" to currentView.fullGraph,
                        "workspaceGraph" to snapshot.workspaceGraph,
                        "semanticFactGraph" to snapshot.semanticFactGraph,
                    ),
                    readable = ::readable,
                )?.let { node ->
                    return resolution(normalizedNodeId, normalizedSymbol, node, trace)
                }
                mappedCanonicalIds.firstReadableTrustedNode(snapshot, ::readable)?.let { node ->
                    return resolution(normalizedNodeId, normalizedSymbol, node, trace)
                }
            }

            listOf(normalizedNodeId).firstReadableFrom(
                documents = listOf(
                    "workspaceGraph" to snapshot.workspaceGraph,
                    "semanticFactGraph" to snapshot.semanticFactGraph,
                ),
                readable = ::readable,
            )?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
            listOf(normalizedNodeId).firstReadableTrustedNode(snapshot, ::readable)?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
        }

        val effectiveSignature = normalizedSymbol ?: snapshot.selectedMethodSignature?.trim()?.takeIf(String::isNotEmpty)
        if (effectiveSignature != null) {
            trace += "selectedMethodSignature:$effectiveSignature"
            findBySignature(snapshot, currentView, effectiveSignature, ::readable)?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
        }

        return QaEvidenceAnchorResolution(
            requestedNodeId = normalizedNodeId,
            requestedSymbolSignature = normalizedSymbol,
            node = fallbackNode,
            resolvedNodeId = fallbackNode?.id,
            mappingTrace = trace.distinct(),
        )
    }

    /** 把命中节点和当前轨迹包装成完整的解析结果。 */
    private fun resolution(
        requestedNodeId: String?,
        requestedSymbolSignature: String?,
        node: GraphNode,
        trace: List<String>,
    ): QaEvidenceAnchorResolution = QaEvidenceAnchorResolution(
        requestedNodeId = requestedNodeId,
        requestedSymbolSignature = requestedSymbolSignature,
        node = node,
        resolvedNodeId = node.id,
        mappingTrace = trace.distinct(),
    )

    /** 根据当前场景 ID 选择对应的可见图、完整图与投影索引。 */
    private fun currentView(snapshot: ToolGraphSnapshot): CurrentView {
        return when (snapshot.currentSceneId) {
            ToolGraphSceneId.WORKSPACE_FLOWCHART -> CurrentView(
                visibleGraph = snapshot.flowchartView.visibleGraph,
                fullGraph = snapshot.flowchartView.fullGraph,
                projectionIndex = snapshot.flowchartView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_RESOURCE_RELATION -> CurrentView(
                visibleGraph = snapshot.resourceRelationView.visibleGraph,
                fullGraph = snapshot.resourceRelationView.fullGraph,
                projectionIndex = snapshot.resourceRelationView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_FACT -> CurrentView(
                visibleGraph = snapshot.factGraphView.visibleGraph,
                fullGraph = snapshot.factGraphView.fullGraph,
                projectionIndex = snapshot.factGraphView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> CurrentView(
                visibleGraph = snapshot.architectureGraphView.visibleGraph,
                fullGraph = snapshot.architectureGraphView.fullGraph,
                projectionIndex = snapshot.architectureGraphView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_CLASS_DIAGRAM -> CurrentView(
                visibleGraph = snapshot.classDiagramView.visibleGraph,
                fullGraph = snapshot.classDiagramView.fullGraph,
                projectionIndex = snapshot.classDiagramView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_REVIEW_GRAPH -> CurrentView(
                visibleGraph = snapshot.reviewGraphView.visibleGraph,
                fullGraph = snapshot.reviewGraphView.fullGraph,
                projectionIndex = snapshot.reviewGraphView.projectionIndex,
            )
            ToolGraphSceneId.DIFF -> CurrentView(
                visibleGraph = snapshot.diffGraph ?: GraphDocument(),
                fullGraph = snapshot.diffGraph ?: GraphDocument(),
                projectionIndex = ToolGraphProjectionIndex.EMPTY,
            )
        }
    }

    /** 按节点 ID 顺序在多张图中查找首个可读节点，命中即返回。 */
    private fun List<String>.firstReadableFrom(
        documents: List<Pair<String, GraphDocument>>,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        for (nodeId in this) {
            for ((stage, graph) in documents) {
                readable(graph.findNode(nodeId), stage)?.let { return it }
            }
        }
        return null
    }

    /** 按节点 ID 顺序在信任导航节点集合中查找首个可读节点。 */
    private fun List<String>.firstReadableTrustedNode(
        snapshot: ToolGraphSnapshot,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        for (nodeId in this) {
            readable(snapshot.trustedNavigationNodes[nodeId], "trustedNavigationNodes")?.let { return it }
        }
        return null
    }

    /** 按签名在多张图与信任导航节点中查找首个可读节点，并对候选按节点类型排序后取最优解。 */
    private fun findBySignature(
        snapshot: ToolGraphSnapshot,
        currentView: CurrentView,
        signature: String,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        val documents = listOf(
            "currentGraph" to currentView.visibleGraph,
            "canonicalGraph" to currentView.fullGraph,
            "workspaceGraph" to snapshot.workspaceGraph,
            "semanticFactGraph" to snapshot.semanticFactGraph,
        )
        val graphCandidates = documents.flatMapIndexed { documentIndex, (stage, graph) ->
            graph.nodes
                .filter { node -> node.signature == signature }
                .map { node -> SignatureAnchorCandidate(stage, documentIndex, node) }
        }
        graphCandidates
            .sortedWith(
                compareBy<SignatureAnchorCandidate>(
                    { candidate -> signatureAnchorRank(candidate.node) },
                    SignatureAnchorCandidate::documentIndex,
                    { candidate -> candidate.node.id },
                ),
            )
            .firstNotNullOfOrNull { candidate -> readable(candidate.node, candidate.stage) }
            ?.let { return it }
        return snapshot.trustedNavigationNodes.values
            .filter { node -> node.signature == signature }
            .map { node -> SignatureAnchorCandidate("trustedNavigationNodes", documents.size, node) }
            .sortedWith(compareBy({ candidate -> signatureAnchorRank(candidate.node) }, { candidate -> candidate.node.id }))
            .firstNotNullOfOrNull { candidate -> readable(candidate.node, candidate.stage) }
    }

    /** 在图中查找指定 ID 的首个节点。 */
    private fun GraphDocument.findNode(nodeId: String): GraphNode? = nodes.firstOrNull { node -> node.id == nodeId }

    /** 判断节点是否具备源码元数据，可作为可读节点返回。 */
    private fun hasSourceMetadata(node: GraphNode): Boolean = !node.sourceLocation().filePath.isNullOrBlank()

    /** 给签名匹配候选节点打分，方法类节点优先于普通节点，方法调用节点优先级最低。 */
    private fun signatureAnchorRank(node: GraphNode): Int {
        return when {
            node.type == NodeType.METHOD -> 0
            node.metadata["flow.kind"] == "INVOCATION" -> 2
            else -> 1
        }
    }

    /** 当前场景下可见图、完整图与投影索引的组合视图。 */
    private data class CurrentView(
        val visibleGraph: GraphDocument,
        val fullGraph: GraphDocument,
        val projectionIndex: ToolGraphProjectionIndex,
    )

    /** 单个签名匹配候选，附带来源阶段与文档索引，用于稳定排序。 */
    private data class SignatureAnchorCandidate(
        val stage: String,
        val documentIndex: Int,
        val node: GraphNode,
    )
}
