package com.charmnight.linkgraph.diff

import com.charmnight.linkgraph.mermaid.MermaidBindingService
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch

/** 图差异比较后的聚合结果。 */
data class GraphDifferResult(
    /** 带 diff 标记的图文档。 */
    val graph: GraphDocument,
    /** 汇总后的 diff 结果。 */
    val diff: GraphDiff,
)

/**
 * 比较代码图与 Mermaid 图之间的差异。
 * 在正式比较前会先做节点绑定和归一化，避免仅因展示差异产生噪声。
 */
class GraphDiffer(
    /** 图归一化服务。 */
    private val normalizationService: GraphNormalizationService = GraphNormalizationService(),
    /** Mermaid 与代码图的节点绑定服务。 */
    private val bindingService: MermaidBindingService = MermaidBindingService(normalizationService),
) {
    /** 对代码图与 Mermaid 图执行差异比较。 */
    fun diff(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): GraphDifferResult {
        /** 绑定到代码图节点后的 Mermaid 图。 */
        val boundMermaid = bindingService.bind(mermaidGraph, codeGraph)
        /** 归一化后的代码图。 */
        val normalizedCode = normalizationService.normalize(codeGraph)
        /** 归一化后的 Mermaid 图。 */
        val normalizedMermaid = normalizationService.normalize(boundMermaid)

        /** 节点差异结果列表。 */
        val nodeOutcomes = diffNodes(normalizedCode, normalizedMermaid)
        /** 边差异结果列表。 */
        val edgeOutcomes = diffEdges(normalizedCode, normalizedMermaid)
        /** 仅保留发生变化的 diff 条目。 */
        val changedEntries = (nodeOutcomes.mapNotNull { it.entry } + edgeOutcomes.mapNotNull { it.entry })
            .sortedWith(compareBy({ it.elementKind.name }, { it.elementId }))
        /** 面向用户展示的摘要文本。 */
        val summary = buildSummary(changedEntries)

        /** 带 diff 标记和 patch 汇总的图文档。 */
        val graph = GraphDocument(
            nodes = nodeOutcomes.map { it.node }.sortedBy { it.id },
            edges = edgeOutcomes.map { it.edge }.sortedBy { it.id },
            patch = GraphPatch(
                addedNodeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.NODE && it.status == DiffStatus.ONLY_IN_MERMAID }.map { it.elementId },
                removedNodeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.NODE && it.status == DiffStatus.ONLY_IN_CODE }.map { it.elementId },
                addedEdgeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.EDGE && it.status == DiffStatus.ONLY_IN_MERMAID }.map { it.elementId },
                removedEdgeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.EDGE && it.status == DiffStatus.ONLY_IN_CODE }.map { it.elementId },
            ),
        )

        return GraphDifferResult(
            graph = graph,
            diff = GraphDiff(
                status = if (changedEntries.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED,
                summary = summary,
                entries = changedEntries,
            ),
        )
    }

    /** 比较节点层面的差异。 */
    private fun diffNodes(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): List<NodeOutcome> {
        /** 代码图节点索引。 */
        val codeNodesById = codeGraph.nodes.associateBy { it.id }
        /** Mermaid 图节点索引。 */
        val mermaidNodesById = mermaidGraph.nodes.associateBy { it.id }
        /** 需要参与比较的全部节点 ID。 */
        val allIds = (codeNodesById.keys + mermaidNodesById.keys).toSortedSet()

        return allIds.map { id ->
            /** 当前 ID 对应的代码节点。 */
            val codeNode = codeNodesById[id]
            /** 当前 ID 对应的 Mermaid 节点。 */
            val mermaidNode = mermaidNodesById[id]
            when {
                codeNode != null && mermaidNode == null -> {
                    val diff = GraphDiff(
                        status = DiffStatus.ONLY_IN_CODE,
                        message = "代码中存在，但 Mermaid 中缺失。",
                    )
                    NodeOutcome(
                        node = codeNode.copy(diff = diff),
                        entry = GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = codeNode.id,
                            status = DiffStatus.ONLY_IN_CODE,
                            message = diff.message,
                        ),
                    )
                }

                codeNode == null && mermaidNode != null -> {
                    val diff = GraphDiff(
                        status = DiffStatus.ONLY_IN_MERMAID,
                        message = "Mermaid 中存在，但代码中缺失。",
                    )
                    NodeOutcome(
                        node = mermaidNode.copy(diff = diff),
                        entry = GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = mermaidNode.id,
                            status = DiffStatus.ONLY_IN_MERMAID,
                            message = diff.message,
                        ),
                    )
                }

                codeNode != null && mermaidNode != null -> {
                    /** 两个节点之间发生变化的字段列表。 */
                    val fields = compareNodes(codeNode, mermaidNode)
                    /** 当前节点 diff 状态。 */
                    val status = if (fields.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED
                    val diff = GraphDiff(
                        status = status,
                        fields = fields,
                        counterpartId = mermaidNode.id,
                        message = fields.takeIf { it.isNotEmpty() }?.joinToString(
                            prefix = "差异字段：",
                            separator = ", ",
                        ),
                    )
                    NodeOutcome(
                        node = codeNode.copy(
                            diff = diff,
                            bindingStatus = if (status == DiffStatus.MODIFIED) BindingStatus.PARTIALLY_SYNCED else codeNode.bindingStatus,
                        ),
                        entry = diff.takeIf { it.status != DiffStatus.MATCHED }?.let {
                            GraphDiffEntry(
                                elementKind = GraphDiffElementKind.NODE,
                                elementId = codeNode.id,
                                counterpartId = mermaidNode.id,
                                status = it.status,
                                fields = it.fields,
                                message = it.message,
                            )
                        },
                    )
                }

                else -> error("Unreachable node diff branch.")
            }
        }
    }

    /** 比较边层面的差异。 */
    private fun diffEdges(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): List<EdgeOutcome> {
        /** 代码图边索引。 */
        val codeEdgesById = codeGraph.edges.associateBy { it.id }
        /** Mermaid 图边索引。 */
        val mermaidEdgesById = mermaidGraph.edges.associateBy { it.id }
        /** 尚未匹配的代码边。 */
        val remainingCode = codeEdgesById.toMutableMap()
        /** 尚未匹配的 Mermaid 边。 */
        val remainingMermaid = mermaidEdgesById.toMutableMap()
        /** 边 diff 结果列表。 */
        val outcomes = mutableListOf<EdgeOutcome>()

        /** 两侧稳定 ID 完全相同的边。 */
        val matchedIds = (codeEdgesById.keys intersect mermaidEdgesById.keys).sorted()
        matchedIds.forEach { id ->
            val codeEdge = remainingCode.remove(id) ?: return@forEach
            val mermaidEdge = remainingMermaid.remove(id) ?: return@forEach
            outcomes += buildMatchedEdgeOutcome(codeEdge, mermaidEdge)
        }

        /** 对稳定 ID 不同但可能只是字段被改动的边做二次匹配。 */
        remainingCode.values.sortedBy { it.id }.forEach { codeEdge ->
            val candidate = findModifiedEdgeCandidate(codeEdge, remainingMermaid.values.toList())
            if (candidate != null) {
                remainingCode.remove(codeEdge.id)
                remainingMermaid.remove(candidate.id)
                outcomes += buildMatchedEdgeOutcome(codeEdge, candidate)
            }
        }

        remainingCode.values.sortedBy { it.id }.forEach { codeEdge ->
            val diff = GraphDiff(
                status = DiffStatus.ONLY_IN_CODE,
                message = "边在代码中存在，但 Mermaid 中缺失。",
            )
            outcomes += EdgeOutcome(
                edge = codeEdge.copy(diff = diff),
                entry = GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = codeEdge.id,
                    status = DiffStatus.ONLY_IN_CODE,
                    message = diff.message,
                ),
            )
        }

        remainingMermaid.values.sortedBy { it.id }.forEach { mermaidEdge ->
            val diff = GraphDiff(
                status = DiffStatus.ONLY_IN_MERMAID,
                message = "边在 Mermaid 中存在，但代码中缺失。",
            )
            outcomes += EdgeOutcome(
                edge = mermaidEdge.copy(diff = diff),
                entry = GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = mermaidEdge.id,
                    status = DiffStatus.ONLY_IN_MERMAID,
                    message = diff.message,
                ),
            )
        }

        return outcomes.sortedBy { it.edge.id }
    }

    /** 构造一条“已匹配边”的 diff 结果。 */
    private fun buildMatchedEdgeOutcome(
        codeEdge: GraphEdge,
        mermaidEdge: GraphEdge,
    ): EdgeOutcome {
        /** 两条边之间发生变化的字段列表。 */
        val fields = compareEdges(codeEdge, mermaidEdge)
        /** 当前边 diff 状态。 */
        val status = if (fields.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED
        val diff = GraphDiff(
            status = status,
            fields = fields,
            counterpartId = mermaidEdge.id,
            message = fields.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "差异字段：",
                separator = ", ",
            ),
        )
        return EdgeOutcome(
            edge = codeEdge.copy(
                diff = diff,
                bindingStatus = if (status == DiffStatus.MODIFIED) BindingStatus.PARTIALLY_SYNCED else codeEdge.bindingStatus,
            ),
            entry = diff.takeIf { it.status != DiffStatus.MATCHED }?.let {
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = codeEdge.id,
                    counterpartId = mermaidEdge.id,
                    status = it.status,
                    fields = it.fields,
                    message = it.message,
                )
            },
        )
    }

    /** 在未匹配边中寻找最像“同一条边被修改后”的候选。 */
    private fun findModifiedEdgeCandidate(
        codeEdge: GraphEdge,
        mermaidEdges: List<GraphEdge>,
    ): GraphEdge? {
        /** Mermaid 候选边与相似度分数。 */
        val scored = mermaidEdges
            .asSequence()
            .filter { it.type == codeEdge.type }
            .map { candidate -> candidate to edgeSimilarityScore(codeEdge, candidate) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<GraphEdge, Int>> { it.second }.thenBy { it.first.id })
            .toList()
        /** 分数最高的候选边。 */
        val best = scored.firstOrNull() ?: return null
        /** 第二名候选的分数，用于避免并列歧义。 */
        val secondScore = scored.getOrNull(1)?.second
        return if (secondScore == null || best.second > secondScore) {
            best.first
        } else {
            null
        }
    }

    /** 计算两条边的相似度分数。 */
    private fun edgeSimilarityScore(
        left: GraphEdge,
        right: GraphEdge,
    ): Int {
        /** 当前累计分数。 */
        var score = 0
        if (left.fromNodeId == right.fromNodeId) {
            score += 4
        }
        if (left.toNodeId == right.toNodeId) {
            score += 4
        }
        if (!left.label.isNullOrBlank() && left.label == right.label) {
            score += 2
        }
        return score
    }

    /** 逐字段比较两个节点。 */
    private fun compareNodes(
        codeNode: GraphNode,
        mermaidNode: GraphNode,
    ): List<String> {
        /** 发生变化的字段名称列表。 */
        val fields = mutableListOf<String>()
        if (codeNode.type != mermaidNode.type) {
            fields += "type"
        }
        if (codeNode.title != mermaidNode.title) {
            fields += "title"
        }
        if (codeNode.location != mermaidNode.location) {
            fields += "location"
        }
        if (codeNode.signature != mermaidNode.signature) {
            fields += "signature"
        }
        if (codeNode.inputs != mermaidNode.inputs) {
            fields += "inputs"
        }
        if (codeNode.outputs != mermaidNode.outputs) {
            fields += "outputs"
        }
        if (codeNode.doc != mermaidNode.doc) {
            fields += "doc"
        }
        if (codeNode.uncertainty?.reason != mermaidNode.uncertainty?.reason) {
            fields += "uncertainty.reason"
        }
        if (codeNode.uncertainty?.confidence != mermaidNode.uncertainty?.confidence) {
            fields += "uncertainty.confidence"
        }
        fields += diffMetadata(codeNode.metadata, mermaidNode.metadata)
        return fields
    }

    /** 逐字段比较两条边。 */
    private fun compareEdges(
        codeEdge: GraphEdge,
        mermaidEdge: GraphEdge,
    ): List<String> {
        /** 发生变化的字段名称列表。 */
        val fields = mutableListOf<String>()
        if (codeEdge.type != mermaidEdge.type) {
            fields += "type"
        }
        if (codeEdge.fromNodeId != mermaidEdge.fromNodeId) {
            fields += "fromNodeId"
        }
        if (codeEdge.toNodeId != mermaidEdge.toNodeId) {
            fields += "toNodeId"
        }
        if (codeEdge.label != mermaidEdge.label) {
            fields += "label"
        }
        if (codeEdge.uncertainty?.reason != mermaidEdge.uncertainty?.reason) {
            fields += "uncertainty.reason"
        }
        if (codeEdge.uncertainty?.confidence != mermaidEdge.uncertainty?.confidence) {
            fields += "uncertainty.confidence"
        }
        fields += diffMetadata(codeEdge.metadata, mermaidEdge.metadata)
        return fields
    }

    /** 比较两侧元数据键值差异。 */
    private fun diffMetadata(
        left: Map<String, String>,
        right: Map<String, String>,
    ): List<String> {
        return (left.keys + right.keys)
            .toSortedSet()
            .mapNotNull { key ->
                if (left[key] != right[key]) {
                    "metadata.$key"
                } else {
                    null
                }
            }
    }

    /** 构建整体 diff 摘要。 */
    private fun buildSummary(entries: List<GraphDiffEntry>): String {
        if (entries.isEmpty()) {
            return "当前没有图差异。"
        }
        /** 节点和边两个维度的摘要片段。 */
        val parts = mutableListOf<String>()
        summarize(entries, GraphDiffElementKind.NODE)?.let { parts += "节点：$it" }
        summarize(entries, GraphDiffElementKind.EDGE)?.let { parts += "边：$it" }
        return parts.joinToString("；")
    }

    /** 按元素类型汇总 diff 条目数量。 */
    private fun summarize(
        entries: List<GraphDiffEntry>,
        kind: GraphDiffElementKind,
    ): String? {
        /** 当前元素类型下各状态的计数。 */
        val counts = entries
            .filter { it.elementKind == kind }
            .groupingBy { it.status }
            .eachCount()
        if (counts.isEmpty()) {
            return null
        }
        return listOfNotNull(
            counts[DiffStatus.ONLY_IN_CODE]?.let { "$it 个仅代码存在" },
            counts[DiffStatus.ONLY_IN_MERMAID]?.let { "$it 个仅 Mermaid 存在" },
            counts[DiffStatus.MODIFIED]?.let { "$it 个已修改" },
        ).joinToString("，")
    }

    /** 节点比较后的中间结果。 */
    private data class NodeOutcome(
        /** 带 diff 标记的节点。 */
        val node: GraphNode,
        /** 可选的 diff 条目。 */
        val entry: GraphDiffEntry?,
    )

    /** 边比较后的中间结果。 */
    private data class EdgeOutcome(
        /** 带 diff 标记的边。 */
        val edge: GraphEdge,
        /** 可选的 diff 条目。 */
        val entry: GraphDiffEntry?,
    )
}
