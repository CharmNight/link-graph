package com.charmnight.linkgraph.application.planning

import com.charmnight.linkgraph.llm.EvidenceTraceEntry
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.ArrayDeque

internal data class AuditEvidenceCollection(
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    val evidenceTrace: List<EvidenceTraceEntry> = emptyList(),
)

internal class AuditEvidenceCollector(
    private val maxSnippets: Int = 12,
    private val maxTraversalDepth: Int = 2,
    private val preferredSnippetLength: Int = 240,
    private val maxSnippetLength: Int = 800,
) {
    fun collect(
        graph: GraphDocument,
        selectedNodeIds: List<String>,
    ): AuditEvidenceCollection {
        val nodeById = graph.nodes.associateBy(GraphNode::id)
        val seedIds = selectedNodeIds.ifEmpty { graph.nodes.firstOrNull()?.let(GraphNode::id)?.let(::listOf).orEmpty() }
        if (seedIds.isEmpty()) {
            return AuditEvidenceCollection()
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
            if (current.depth >= maxTraversalDepth) {
                continue
            }
            relatedCallNodes(graph.edges, current.nodeId).forEach { nextNodeId ->
                if (nextNodeId !in visitedNodeIds) {
                    queue += TraversalNode(nextNodeId, current.depth + 1, "reached-from:${current.nodeId}")
                }
            }
        }
        return AuditEvidenceCollection(
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
        val filePath = node.metadata["source.filePath"] ?: return null
        val startOffset = node.metadata["source.startOffset"]?.toIntOrNull()
        val endOffset = node.metadata["source.endOffset"]?.toIntOrNull()
        return SourceSnippetContext(
            nodeId = node.id,
            filePath = filePath,
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = node.metadata["source.startLine"]?.toIntOrNull(),
            endLine = node.metadata["source.endLine"]?.toIntOrNull(),
            snippet = loadSnippet(filePath, startOffset, endOffset),
        )
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
