package com.charmnight.linkgraph.foundation

import com.charmnight.linkgraph.model.GraphDocument
import java.util.Locale

internal object LinkGraphRenderTrace {
    fun trace(
        enabled: Boolean,
        log: (String) -> Unit,
        message: () -> String,
    ) {
        if (!enabled) {
            return
        }
        log(message())
    }

    fun stage(
        enabled: Boolean,
        log: (String) -> Unit,
        stage: String,
        startedAtNanos: Long,
        finishedAtNanos: Long = System.nanoTime(),
        details: () -> List<String> = { emptyList() },
    ) {
        trace(enabled, log) {
            val durationMs = (finishedAtNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000.0
            buildString {
                append("渲染链路 trace: stage=")
                append(stage)
                append(", durationMs=")
                append(String.format(Locale.ROOT, "%.2f", durationMs))
                details().filter { it.isNotBlank() }.forEach { detail ->
                    append(", ")
                    append(detail)
                }
            }
        }
    }

    fun graphSummary(graph: GraphDocument?): String {
        if (graph == null) {
            return "nodes=0, edges=0"
        }
        val nodeTypes = graph.nodes
            .groupingBy { node -> node.type.name }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(separator = "|") { (type, count) -> "$type:$count" }
            .ifBlank { "none" }
        val sampleNodeIds = graph.nodes
            .asSequence()
            .map { node -> node.id }
            .sorted()
            .take(6)
            .joinToString(separator = "|")
            .ifBlank { "none" }
        return "nodes=${graph.nodes.size}, edges=${graph.edges.size}, nodeTypes=$nodeTypes, sampleNodeIds=$sampleNodeIds"
    }
}
