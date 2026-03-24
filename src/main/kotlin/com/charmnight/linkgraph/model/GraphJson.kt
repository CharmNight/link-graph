package com.charmnight.linkgraph.model

object GraphJson {
    fun toJson(document: GraphDocument): String {
        val sortedNodes = document.nodes.sortedBy { it.id }
        val sortedEdges = document.edges.sortedBy { it.id }
        val root = linkedMapOf<String, Any?>(
            "nodes" to sortedNodes.map { nodeToMap(it) },
            "edges" to sortedEdges.map { edgeToMap(it) },
            "patch" to document.patch?.let { patchToMap(it) },
        )
        return buildString { appendJsonValue(this, root) }
    }

    fun fromJson(json: String): GraphDocument {
        val root = JsonParser(json).parseValue() as? Map<*, *>
            ?: error("Graph JSON root must be an object.")

        val nodes = (root["nodes"] as? List<*>).orEmpty().map { parseNode(it as Map<*, *>) }
        val edges = (root["edges"] as? List<*>).orEmpty().map { parseEdge(it as Map<*, *>) }
        val patch = (root["patch"] as? Map<*, *>)?.let { parsePatch(it) }

        return GraphDocument(nodes = nodes, edges = edges, patch = patch)
    }

    private fun nodeToMap(node: GraphNode): Map<String, Any?> = linkedMapOf(
        "id" to node.id,
        "type" to node.type.name,
        "title" to node.title,
        "location" to node.location,
        "signature" to node.signature,
        "inputs" to node.inputs,
        "outputs" to node.outputs,
        "doc" to node.doc,
        "sourceKind" to node.sourceKind,
        "status" to node.status,
        "bindingStatus" to node.bindingStatus.name,
        "certainty" to node.certainty.name,
        "diff" to diffToMap(node.diff),
        "evidence" to node.evidence.map { evidenceToMap(it) },
        "uncertainty" to node.uncertainty?.let { uncertaintyToMap(it) },
        "metadata" to node.metadata.toSortedMap(),
    )

    private fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
        "id" to edge.id,
        "type" to edge.type.name,
        "fromNodeId" to edge.fromNodeId,
        "toNodeId" to edge.toNodeId,
        "label" to edge.label,
        "certainty" to edge.certainty.name,
        "status" to edge.status,
        "diff" to diffToMap(edge.diff),
        "evidence" to edge.evidence.map { evidenceToMap(it) },
        "uncertainty" to edge.uncertainty?.let { uncertaintyToMap(it) },
        "metadata" to edge.metadata.toSortedMap(),
    )

    private fun patchToMap(patch: GraphPatch): Map<String, Any?> = linkedMapOf(
        "addedNodeIds" to patch.addedNodeIds,
        "removedNodeIds" to patch.removedNodeIds,
        "addedEdgeIds" to patch.addedEdgeIds,
        "removedEdgeIds" to patch.removedEdgeIds,
    )

    private fun uncertaintyToMap(uncertainty: GraphUncertainty): Map<String, Any?> = linkedMapOf(
        "reason" to uncertainty.reason,
        "confidence" to uncertainty.confidence,
    )

    private fun evidenceToMap(evidence: GraphEvidence): Map<String, Any?> = linkedMapOf(
        "source" to evidence.source,
        "detail" to evidence.detail,
    )

    private fun diffToMap(diff: GraphDiff): Map<String, Any?> = linkedMapOf(
        "status" to diff.status.name,
    )

    private fun parseNode(raw: Map<*, *>): GraphNode {
        return GraphNode(
            id = raw.requiredString("id"),
            type = NodeType.valueOf(raw.requiredString("type")),
            title = raw.optionalString("title") ?: raw.requiredString("label"),
            location = raw.optionalString("location"),
            signature = raw.optionalString("signature"),
            inputs = parseStringList(raw["inputs"] as? List<*>),
            outputs = parseStringList(raw["outputs"] as? List<*>),
            doc = raw.optionalString("doc"),
            sourceKind = raw.optionalString("sourceKind"),
            status = raw.optionalString("status"),
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            diff = parseDiff(raw["diff"] as? Map<*, *>),
            evidence = parseEvidenceList(raw["evidence"] as? List<*>),
            uncertainty = parseUncertainty(raw["uncertainty"] as? Map<*, *>),
            metadata = parseStringMap(raw["metadata"] as? Map<*, *>),
        )
    }

    private fun parseEdge(raw: Map<*, *>): GraphEdge {
        return GraphEdge(
            id = raw.requiredString("id"),
            type = EdgeType.valueOf(raw.requiredString("type")),
            fromNodeId = raw.requiredString("fromNodeId"),
            toNodeId = raw.requiredString("toNodeId"),
            label = raw.optionalString("label"),
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            status = raw.optionalString("status"),
            diff = parseDiff(raw["diff"] as? Map<*, *>),
            evidence = parseEvidenceList(raw["evidence"] as? List<*>),
            uncertainty = parseUncertainty(raw["uncertainty"] as? Map<*, *>),
            metadata = parseStringMap(raw["metadata"] as? Map<*, *>),
        )
    }

    private fun parsePatch(raw: Map<*, *>): GraphPatch {
        return GraphPatch(
            addedNodeIds = parseStringList(raw["addedNodeIds"] as? List<*>),
            removedNodeIds = parseStringList(raw["removedNodeIds"] as? List<*>),
            addedEdgeIds = parseStringList(raw["addedEdgeIds"] as? List<*>),
            removedEdgeIds = parseStringList(raw["removedEdgeIds"] as? List<*>),
        )
    }

    private fun parseDiff(raw: Map<*, *>?): GraphDiff {
        if (raw == null) {
            return GraphDiff()
        }
        val status = raw.requiredString("status")
        return GraphDiff(status = DiffStatus.valueOf(status))
    }

    private fun parseEvidenceList(raw: List<*>?): List<GraphEvidence> {
        return raw.orEmpty().map {
            val map = it as? Map<*, *> ?: return@map GraphEvidence(source = "unknown")
            GraphEvidence(
                source = map.requiredString("source"),
                detail = map.optionalString("detail"),
            )
        }
    }

    private fun parseUncertainty(raw: Map<*, *>?): GraphUncertainty? {
        if (raw == null) {
            return null
        }
        return GraphUncertainty(
            reason = raw.requiredString("reason"),
            confidence = (raw["confidence"] as? Number)?.toDouble(),
        )
    }

    private fun parseStringMap(raw: Map<*, *>?): Map<String, String> {
        if (raw == null) {
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            if (key is String && value is String) {
                result[key] = value
            }
        }
        return result
    }

    private fun parseStringList(raw: List<*>?): List<String> = raw.orEmpty().mapNotNull { it as? String }

    private fun Map<*, *>.requiredString(key: String): String {
        return this[key] as? String ?: error("Expected string field '$key'.")
    }

    private fun Map<*, *>.optionalString(key: String): String? = this[key] as? String

    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(key: String, defaultValue: T): T {
        val raw = this[key] as? String ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
    }

    private fun appendJsonValue(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> builder.append('"').append(escape(value)).append('"')
            is Boolean, is Int, is Long -> builder.append(value.toString())
            is Float -> builder.append(formatNumber(value.toDouble()))
            is Double -> builder.append(formatNumber(value))
            is Number -> builder.append(formatNumber(value.toDouble()))
            is Map<*, *> -> {
                builder.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { index, entry ->
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

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            return "null"
        }
        if (value % 1.0 == 0.0) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    private fun escape(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

private class JsonParser(private val text: String) {
    private var index: Int = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (index >= text.length) {
            error("Unexpected end of input.")
        }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected token '${text[index]}' at $index")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, Any?>()
        if (peek('}')) {
            expect('}')
            return result
        }
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            result[key] = value
            skipWhitespace()
            if (peek('}')) {
                expect('}')
                break
            }
            expect(',')
        }
        return result
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        val result = mutableListOf<Any?>()
        if (peek(']')) {
            expect(']')
            return result
        }
        while (true) {
            val value = parseValue()
            result.add(value)
            skipWhitespace()
            if (peek(']')) {
                expect(']')
                break
            }
            expect(',')
        }
        return result
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) {
                        error("Unterminated escape at $index")
                    }
                    val escaped = text[index++]
                    when (escaped) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) {
                                error("Invalid unicode escape at $index")
                            }
                            val hex = text.substring(index, index + 4)
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }

                        else -> error("Unsupported escape '\\$escaped'")
                    }
                }

                else -> out.append(ch)
            }
        }
        error("Unterminated string")
    }

    private fun parseNumber(): Number {
        val start = index
        if (text[index] == '-') {
            index++
        }
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        var isFloat = false
        if (index < text.length && text[index] == '.') {
            isFloat = true
            index++
            while (index < text.length && text[index].isDigit()) {
                index++
            }
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            isFloat = true
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                index++
            }
            while (index < text.length && text[index].isDigit()) {
                index++
            }
        }
        val numberText = text.substring(start, index)
        return if (isFloat) numberText.toDouble() else numberText.toLong()
    }

    private fun parseLiteral(expected: String, value: Any?): Any? {
        if (text.regionMatches(index, expected, 0, expected.length)) {
            index += expected.length
            return value
        }
        error("Expected '$expected' at $index")
    }

    private fun expect(ch: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != ch) {
            error("Expected '$ch' at $index")
        }
        index++
    }

    private fun peek(ch: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == ch
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }
}
