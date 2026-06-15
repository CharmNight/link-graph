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

internal data class QaEvidenceCollection(
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    val evidenceTrace: List<EvidenceTraceEntry> = emptyList(),
)

internal class QaEvidenceCollector(
    private val maxSnippets: Int = 12,
    private val maxTraversalDepth: Int = 2,
    private val preferredSnippetLength: Int = 240,
    private val maxSnippetLength: Int = 800,
) {
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

    private data class TraversalNode(
        val nodeId: String,
        val depth: Int,
        val reason: String,
    )
}
