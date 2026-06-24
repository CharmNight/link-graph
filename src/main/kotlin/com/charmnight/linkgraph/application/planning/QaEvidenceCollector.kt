package com.charmnight.linkgraph.application.planning

import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceLocation
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.ArrayDeque

/**
 * 问答上下文证据采集结果，包含收集到的源码片段以及对应的追溯信息。
 */
internal data class QaEvidenceCollection(
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    val evidenceTrace: List<EvidenceTraceEntry> = emptyList(),
)

/**
 * 在用户问答场景下，从图谱节点出发收集源码上下文证据的采集器。
 * 通过广度优先遍历选定节点的关联节点，按预算抽取源码片段用于构造问答上下文。
 */
internal class QaEvidenceCollector(
    private val maxSnippets: Int = 12,
    private val maxTraversalDepth: Int = 2,
    private val preferredSnippetLength: Int = 240,
    private val maxSnippetLength: Int = 800,
) {
    /**
     * 以选定的节点为种子，在图中遍历有限层数，并将收集到的源码片段整理为问答上下文证据。
     */
    fun collect(
        graph: GraphDocument,
        selectedNodeIds: List<String>,
    ): QaEvidenceCollection {
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        val seedIds = selectedNodeIds.ifEmpty { graph.nodes.firstOrNull()?.let(GraphNode::id)?.let(::listOf).orEmpty() }
        if (seedIds.isEmpty()) {
            return QaEvidenceCollection()
        }
        val visitedNodeIds = linkedSetOf<String>()
        val snippets = mutableListOf<SourceSnippetContext>()
        val trace = mutableListOf<EvidenceTraceEntry>()
        val queue = ArrayDeque(seedIds.map { nodeId -> TraversalNode(nodeId, 0, "selected-scope") })

        while (queue.isNotEmpty() && snippets.size < maxSnippets) {
            val current = queue.removeFirst()
            if (!visitedNodeIds.add(current.nodeId)) {
                continue
            }
            val node = nodeById[current.nodeId] ?: continue
            readSourceSnippet(node)?.let { snippet ->
                snippets += snippet
                trace += EvidenceTraceEntry(
                    nodeId = node.id,
                    filePath = snippet.filePath,
                    reason = current.reason,
                    startLine = snippet.startLine,
                    endLine = snippet.endLine,
                    includedInPrompt = true,
                )
            }
            architectureSourceSampleSnippets(node)
                .take(maxSnippets - snippets.size)
                .forEach { sampleSnippet ->
                    snippets += sampleSnippet
                    trace += EvidenceTraceEntry(
                        nodeId = current.nodeId,
                        resolvedNodeId = sampleSnippet.nodeId,
                        filePath = sampleSnippet.filePath,
                        reason = "architecture-source-sample:${current.nodeId}",
                        startLine = sampleSnippet.startLine,
                        endLine = sampleSnippet.endLine,
                        includedInPrompt = true,
                        mappingTrace = listOf("architectureSourceSample:${current.nodeId}->${sampleSnippet.nodeId}"),
                    )
                }
            if (current.depth >= maxTraversalDepth) {
                continue
            }
            relatedCallNodes(graph.edges, current.nodeId).forEach { nextNodeId ->
                if (nextNodeId !in visitedNodeIds) {
                    queue += TraversalNode(nextNodeId, current.depth + 1, "reached-from:${current.nodeId}")
                }
            }
        }
        return QaEvidenceCollection(
            sourceContext = snippets,
            evidenceTrace = trace,
        )
    }

    /**
     * 通过边集合找到与指定节点直接相连的其它节点（去掉节点自身）。
     */
    private fun relatedCallNodes(
        edges: List<GraphEdge>,
        nodeId: String,
    ): List<String> {
        return edges.asSequence()
            .filter { edge -> edge.fromNodeId == nodeId || edge.toNodeId == nodeId }
            .flatMap { edge ->
                sequenceOf(edge.fromNodeId, edge.toNodeId)
            }
            .filter { relatedNodeId -> relatedNodeId != nodeId }
            .distinct()
            .toList()
    }

    /**
     * 读取单个节点自身源码位置对应的代码片段，将其封装为上下文对象。
     * 若节点没有有效的文件路径则返回 null。
     */
    private fun readSourceSnippet(node: GraphNode): SourceSnippetContext? {
        val sourceLocation = node.sourceLocation()
        val filePath = sourceLocation.filePath ?: return null
        val startOffset = sourceLocation.startOffset
        val endOffset = sourceLocation.endOffset
        return SourceSnippetContext(
            nodeId = node.id,
            filePath = filePath,
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = sourceLocation.startLine,
            endLine = sourceLocation.endLine,
            snippet = loadSnippet(filePath, startOffset, endOffset),
        )
    }

    /**
     * 从节点 metadata 中读取架构分析时记录的关联源码样本，例如反编译产物、跨文件代码示例等。
     * 这些样本作为节点自身源码的补充，提供更丰富的上下文。
     */
    private fun architectureSourceSampleSnippets(node: GraphNode): List<SourceSnippetContext> {
        val sampleCount = node.metadata["architecture.sourceSample.count"]?.toIntOrNull()?.coerceAtLeast(0) ?: return emptyList()
        return (0 until sampleCount).mapNotNull { sampleIndex ->
            val prefix = "architecture.sourceSample.$sampleIndex"
            val filePath = node.metadata["$prefix.filePath"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val nodeId = node.metadata["$prefix.nodeId"]?.takeIf(String::isNotBlank) ?: node.id
            SourceSnippetContext(
                nodeId = nodeId,
                filePath = filePath,
                startLine = node.metadata["$prefix.startLine"]?.toIntOrNull(),
                endLine = node.metadata["$prefix.endLine"]?.toIntOrNull(),
                snippet = loadSnippet(filePath, startOffset = null, endOffset = null),
                origin = node.metadata["$prefix.reason"],
                decompiled = node.metadata["$prefix.decompiled"]?.toBooleanStrictOrNull() ?: false,
                virtualFileUrl = node.metadata["$prefix.virtualFileUrl"],
            )
        }
    }

    /**
     * 从磁盘读取指定文件内容，并根据给定的字节偏移截取代码片段；不传偏移时仅截取首部有限长度。
     * 读取失败或文件不存在时返回 null。
     */
    private fun loadSnippet(
        filePath: String,
        startOffset: Int?,
        endOffset: Int?,
    ): String? {
        val path = runCatching { Path.of(filePath) }.getOrNull() ?: return null
        return runCatching {
            if (!Files.isRegularFile(path)) {
                return@runCatching null
            }
            val source = Files.readString(path)
            val snippet = if (startOffset != null && endOffset != null) {
                val safeStart = startOffset.coerceIn(0, source.length)
                val safeEnd = endOffset.coerceIn(safeStart, source.length)
                source.substring(safeStart, safeEnd)
            } else {
                source.take(maxSnippetLength)
            }
            normalizeSnippet(snippet)
        }.recoverCatching { error ->
            when (error) {
                is InvalidPathException -> null
                else -> null
            }
        }.getOrNull()
    }

    /**
     * 规整代码片段：统一换行、去首尾空白，并按预算长度截断。
     * 截断后仍超过最大长度会被裁剪；空内容则视为无效返回 null。
     */
    private fun normalizeSnippet(snippet: String): String? {
        val normalized = snippet
            .replace("\r\n", "\n")
            .trim()
        if (normalized.isBlank()) {
            return null
        }
        return if (normalized.length <= preferredSnippetLength) {
            normalized
        } else {
            normalized.take(maxSnippetLength)
        }
    }

    /**
     * 遍历队列中的节点条目，记录节点 ID、所处深度以及为何进入队列（便于追溯来源）。
     */
    private data class TraversalNode(
        val nodeId: String,
        val depth: Int,
        val reason: String,
    )
}
