package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

class GraphEditorPageRenderer {
    fun render(
        entryHtml: String,
        snapshot: GraphEditorStateService.Snapshot,
    ): String {
        val bootstrapScript = """
            <script>
              window.linkGraphBootstrap = ${bootstrapJson(snapshot)};
            </script>
        """.trimIndent()
        return if (entryHtml.contains("</head>", ignoreCase = true)) {
            entryHtml.replace("</head>", "$bootstrapScript\n</head>", ignoreCase = true)
        } else {
            "$bootstrapScript\n$entryHtml"
        }
    }

    private fun bootstrapJson(snapshot: GraphEditorStateService.Snapshot): String {
        val document = snapshot.graph ?: GraphDocument()
        val payload = linkedMapOf<String, Any?>(
            "graph" to linkedMapOf(
                "nodes" to document.nodes.map(::nodeToMap),
                "edges" to document.edges.map(::edgeToMap),
            ),
            "diffItems" to snapshot.diff?.entries.orEmpty().map { entry ->
                linkedMapOf(
                    "id" to entry.elementId,
                    "title" to resolveDiffTitle(entry, document),
                    "status" to entry.status.name,
                    "description" to (entry.message ?: entry.fields.joinToString()),
                )
            },
            "syncPreviewItems" to emptyList<Map<String, Any?>>(),
            "selectedNodeId" to (snapshot.selectedNodeId ?: document.nodes.firstOrNull()?.id),
        )
        return toJson(payload)
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
    }

    private fun resolveDiffTitle(
        entry: com.charmnight.linkgraph.model.GraphDiffEntry,
        document: GraphDocument,
    ): String {
        return when (entry.elementKind) {
            GraphDiffElementKind.NODE -> document.nodes.firstOrNull { it.id == entry.elementId }?.title ?: entry.elementId
            GraphDiffElementKind.EDGE -> entry.elementId
        }
    }

    private fun nodeToMap(node: GraphNode): Map<String, Any?> = linkedMapOf(
        "id" to node.id,
        "type" to node.type.name,
        "title" to node.title,
        "signature" to node.signature,
        "doc" to node.doc,
        "certainty" to node.certainty.name,
        "bindingStatus" to node.bindingStatus.name,
        "diffStatus" to node.diff.status.takeUnless { it.name == "MATCHED" }?.name,
    )

    private fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
        "id" to edge.id,
        "type" to edge.type.name,
        "source" to edge.fromNodeId,
        "target" to edge.toNodeId,
        "label" to edge.label,
    )

    private fun toJson(value: Any?): String {
        return buildString {
            appendJsonValue(this, value)
        }
    }

    private fun appendJsonValue(
        builder: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> builder.append("null")
            is String -> builder.append('"').append(escape(value)).append('"')
            is Boolean, is Int, is Long -> builder.append(value.toString())
            is Float -> builder.append(formatNumber(value.toDouble()))
            is Double -> builder.append(formatNumber(value))
            is Number -> builder.append(formatNumber(value.toDouble()))
            is Map<*, *> -> {
                builder.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    builder.append('"').append(escape(entry.key.toString())).append('"').append(':')
                    appendJsonValue(builder, entry.value)
                }
                builder.append('}')
            }

            is Iterable<*> -> {
                builder.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    appendJsonValue(builder, item)
                }
                builder.append(']')
            }

            else -> builder.append('"').append(escape(value.toString())).append('"')
        }
    }

    private fun escape(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            return "null"
        }
        if (value % 1.0 == 0.0) {
            return value.toLong().toString()
        }
        return value.toString()
    }
}
