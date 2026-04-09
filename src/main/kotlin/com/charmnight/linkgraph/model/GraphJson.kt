package com.charmnight.linkgraph.model

/**
 * 负责链路图文档的 JSON 编解码。
 */
object GraphJson {
    /**
     * 将图文档编码为稳定排序的 JSON 字符串。
     */
    fun toJson(document: GraphDocument): String {
        // 节点和边按标识排序，保证同一图多次序列化结果稳定。
        val sortedNodes = document.nodes.sortedBy { it.id }
        val sortedEdges = document.edges.sortedBy { it.id }
        // 使用有序映射构造根对象，保持输出字段顺序固定。
        val root = linkedMapOf<String, Any?>(
            "nodes" to sortedNodes.map { nodeToMap(it) },
            "edges" to sortedEdges.map { edgeToMap(it) },
            "patch" to document.patch?.let { patchToMap(it) },
        )
        return buildString { appendJsonValue(this, root) }
    }

    /**
     * 从 JSON 字符串解析出图文档。
     */
    fun fromJson(json: String): GraphDocument {
        // 根节点必须是 JSON 对象，否则视为非法图文档格式。
        val root = JsonParser(json).parseValue() as? Map<*, *>
            ?: error("Graph JSON root must be an object.")

        // 分别解析节点、边和补丁，缺失字段时回退为空集合。
        val nodes = (root["nodes"] as? List<*>).orEmpty().map { parseNode(it as Map<*, *>) }
        val edges = (root["edges"] as? List<*>).orEmpty().map { parseEdge(it as Map<*, *>) }
        val patch = (root["patch"] as? Map<*, *>)?.let { parsePatch(it) }

        return GraphDocument(nodes = nodes, edges = edges, patch = patch)
    }

    /**
     * 把节点对象转换为可序列化映射。
     */
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
        "sourceTag" to node.sourceTag.name,
    )

    /**
     * 把边对象转换为可序列化映射。
     */
    private fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
        "id" to edge.id,
        "type" to edge.type.name,
        "fromNodeId" to edge.fromNodeId,
        "toNodeId" to edge.toNodeId,
        "label" to edge.label,
        "certainty" to edge.certainty.name,
        "bindingStatus" to edge.bindingStatus.name,
        "status" to edge.status,
        "diff" to diffToMap(edge.diff),
        "evidence" to edge.evidence.map { evidenceToMap(it) },
        "uncertainty" to edge.uncertainty?.let { uncertaintyToMap(it) },
        "metadata" to edge.metadata.toSortedMap(),
        "sourceTag" to edge.sourceTag.name,
    )

    /**
     * 把图补丁转换为可序列化映射。
     */
    private fun patchToMap(patch: GraphPatch): Map<String, Any?> = linkedMapOf(
        "summary" to patch.summary,
        "operations" to patch.operations.map { operationToMap(it) },
        "addedNodeIds" to patch.addedNodeIds,
        "removedNodeIds" to patch.removedNodeIds,
        "addedEdgeIds" to patch.addedEdgeIds,
        "removedEdgeIds" to patch.removedEdgeIds,
    )

    /**
     * 把单个补丁操作转换为可序列化映射。
     */
    private fun operationToMap(operation: GraphPatchOperation): Map<String, Any?> = linkedMapOf(
        "id" to operation.id,
        "action" to operation.action.name,
        "elementKind" to operation.elementKind.name,
        "elementId" to operation.elementId,
        "title" to operation.title,
        "summary" to operation.summary,
        "node" to operation.node?.let { nodeToMap(it) },
        "edge" to operation.edge?.let { edgeToMap(it) },
        "metadata" to operation.metadata.toSortedMap(),
    )

    /**
     * 把不确定信息转换为可序列化映射。
     */
    private fun uncertaintyToMap(uncertainty: GraphUncertainty): Map<String, Any?> = linkedMapOf(
        "reason" to uncertainty.reason,
        "confidence" to uncertainty.confidence,
    )

    /**
     * 把证据信息转换为可序列化映射。
     */
    private fun evidenceToMap(evidence: GraphEvidence): Map<String, Any?> = linkedMapOf(
        "source" to evidence.source,
        "detail" to evidence.detail,
    )

    /**
     * 把差异信息转换为可序列化映射。
     */
    private fun diffToMap(diff: GraphDiff): Map<String, Any?> = linkedMapOf(
        "status" to diff.status.name,
        "fields" to diff.fields,
        "counterpartId" to diff.counterpartId,
        "message" to diff.message,
        "summary" to diff.summary,
        "entries" to diff.entries.map { diffEntryToMap(it) },
    )

    /**
     * 把单条差异记录转换为可序列化映射。
     */
    private fun diffEntryToMap(entry: GraphDiffEntry): Map<String, Any?> = linkedMapOf(
        "elementKind" to entry.elementKind.name,
        "elementId" to entry.elementId,
        "status" to entry.status.name,
        "counterpartId" to entry.counterpartId,
        "fields" to entry.fields,
        "message" to entry.message,
    )

    /**
     * 从原始映射解析节点对象。
     */
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
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
        )
    }

    /**
     * 从原始映射解析边对象。
     */
    private fun parseEdge(raw: Map<*, *>): GraphEdge {
        return GraphEdge(
            id = raw.requiredString("id"),
            type = EdgeType.valueOf(raw.requiredString("type")),
            fromNodeId = raw.requiredString("fromNodeId"),
            toNodeId = raw.requiredString("toNodeId"),
            label = raw.optionalString("label"),
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            status = raw.optionalString("status"),
            diff = parseDiff(raw["diff"] as? Map<*, *>),
            evidence = parseEvidenceList(raw["evidence"] as? List<*>),
            uncertainty = parseUncertainty(raw["uncertainty"] as? Map<*, *>),
            metadata = parseStringMap(raw["metadata"] as? Map<*, *>),
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
        )
    }

    /**
     * 从原始映射解析图补丁。
     */
    private fun parsePatch(raw: Map<*, *>): GraphPatch {
        return GraphPatch(
            summary = raw.optionalString("summary"),
            operations = parsePatchOperations(raw["operations"] as? List<*>),
            addedNodeIds = parseStringList(raw["addedNodeIds"] as? List<*>),
            removedNodeIds = parseStringList(raw["removedNodeIds"] as? List<*>),
            addedEdgeIds = parseStringList(raw["addedEdgeIds"] as? List<*>),
            removedEdgeIds = parseStringList(raw["removedEdgeIds"] as? List<*>),
        )
    }

    /**
     * 解析补丁操作列表。
     */
    private fun parsePatchOperations(raw: List<*>?): List<GraphPatchOperation> {
        return raw.orEmpty().mapNotNull {
            // 仅处理对象形态的操作条目，其他内容直接忽略。
            val map = it as? Map<*, *> ?: return@mapNotNull null
            GraphPatchOperation(
                id = map.requiredString("id"),
                action = GraphPatchAction.valueOf(map.requiredString("action")),
                elementKind = GraphDiffElementKind.valueOf(map.requiredString("elementKind")),
                elementId = map.requiredString("elementId"),
                title = map.optionalString("title"),
                summary = map.optionalString("summary"),
                node = (map["node"] as? Map<*, *>)?.let(::parseNode),
                edge = (map["edge"] as? Map<*, *>)?.let(::parseEdge),
                metadata = parseStringMap(map["metadata"] as? Map<*, *>),
            )
        }
    }

    /**
     * 解析差异摘要对象。
     */
    private fun parseDiff(raw: Map<*, *>?): GraphDiff {
        if (raw == null) {
            return GraphDiff()
        }
        return GraphDiff(
            status = DiffStatus.valueOf(raw.requiredString("status")),
            fields = parseStringList(raw["fields"] as? List<*>),
            counterpartId = raw.optionalString("counterpartId"),
            message = raw.optionalString("message"),
            summary = raw.optionalString("summary"),
            entries = parseDiffEntries(raw["entries"] as? List<*>),
        )
    }

    /**
     * 解析差异条目列表。
     */
    private fun parseDiffEntries(raw: List<*>?): List<GraphDiffEntry> {
        return raw.orEmpty().mapNotNull {
            // 仅对象条目才能还原成差异记录。
            val map = it as? Map<*, *> ?: return@mapNotNull null
            GraphDiffEntry(
                elementKind = GraphDiffElementKind.valueOf(map.requiredString("elementKind")),
                elementId = map.requiredString("elementId"),
                status = DiffStatus.valueOf(map.requiredString("status")),
                counterpartId = map.optionalString("counterpartId"),
                fields = parseStringList(map["fields"] as? List<*>),
                message = map.optionalString("message"),
            )
        }
    }

    /**
     * 解析证据列表。
     */
    private fun parseEvidenceList(raw: List<*>?): List<GraphEvidence> {
        return raw.orEmpty().map {
            // 非对象证据项保底回退为 unknown，避免整次解析失败。
            val map = it as? Map<*, *> ?: return@map GraphEvidence(source = "unknown")
            GraphEvidence(
                source = map.requiredString("source"),
                detail = map.optionalString("detail"),
            )
        }
    }

    /**
     * 解析不确定信息对象。
     */
    private fun parseUncertainty(raw: Map<*, *>?): GraphUncertainty? {
        if (raw == null) {
            return null
        }
        return GraphUncertainty(
            reason = raw.requiredString("reason"),
            confidence = (raw["confidence"] as? Number)?.toDouble(),
        )
    }

    /**
     * 解析字符串键值映射。
     */
    private fun parseStringMap(raw: Map<*, *>?): Map<String, String> {
        if (raw == null) {
            return emptyMap()
        }
        // 仅保留字符串键和值，避免脏数据污染元信息。
        val result = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            if (key is String && value is String) {
                result[key] = value
            }
        }
        return result
    }

    /**
     * 解析字符串列表。
     */
    private fun parseStringList(raw: List<*>?): List<String> = raw.orEmpty().mapNotNull { it as? String }

    /**
     * 从映射中读取必填字符串字段。
     */
    private fun Map<*, *>.requiredString(key: String): String {
        return this[key] as? String ?: error("Expected string field '$key'.")
    }

    /**
     * 从映射中读取可选字符串字段。
     */
    private fun Map<*, *>.optionalString(key: String): String? = this[key] as? String

    /**
     * 从映射中读取枚举字段，缺失或非法时回退默认值。
     */
    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(key: String, defaultValue: T): T {
        val raw = this[key] as? String ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == raw } ?: defaultValue
    }

    /**
     * 递归把值写入 JSON 字符串。
     */
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
                // 先把 entry 固化成列表，便于稳定按下标输出逗号。
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
                // 数组与列表统一按顺序递归输出每个元素。
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

    /**
     * 规范化数字输出格式。
     */
    private fun formatNumber(value: Double): String {
        // JSON 中不接受 NaN 与 Infinity，统一回退为 null。
        if (value.isNaN() || value.isInfinite()) {
            return "null"
        }
        // 整数形式的浮点数统一输出为整数字面量。
        if (value % 1.0 == 0.0) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     */
    private fun escape(input: String): String {
        // 预估长度创建构建器，减少扩容次数。
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

/**
 * 链路图 JSON 使用的轻量解析器。
 */
private class JsonParser(
    /** 保存待解析的 JSON 文本。 */
    private val text: String,
) {
    /** 保存当前扫描到的字符下标。 */
    private var index: Int = 0

    /**
     * 解析当前游标处的 JSON 值。
     */
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

    /**
     * 解析 JSON 对象。
     */
    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        // 使用有序映射保留对象字段顺序，便于调试和稳定比较。
        val result = linkedMapOf<String, Any?>()
        if (peek('}')) {
            expect('}')
            return result
        }
        while (true) {
            // 对象键始终按字符串解析。
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

    /**
     * 解析 JSON 数组。
     */
    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        // 使用列表按原始顺序收集数组元素。
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

    /**
     * 解析 JSON 字符串。
     */
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
                    // 反斜杠后必须跟一个合法转义字符。
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
                            // `\uXXXX` 形式统一按十六进制解码。
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

    /**
     * 解析 JSON 数字。
     */
    private fun parseNumber(): Number {
        // 记录数字起始位置，便于最终切出完整文本。
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
        // 指数写法会强制转为浮点数处理。
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

    /**
     * 解析固定字面量。
     */
    private fun parseLiteral(expected: String, value: Any?): Any? {
        if (text.regionMatches(index, expected, 0, expected.length)) {
            index += expected.length
            return value
        }
        error("Expected '$expected' at $index")
    }

    /**
     * 断言当前位置是指定字符并推进游标。
     */
    private fun expect(ch: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != ch) {
            error("Expected '$ch' at $index")
        }
        index++
    }

    /**
     * 判断当前非空白字符是否为指定字符。
     */
    private fun peek(ch: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == ch
    }

    /**
     * 跳过当前游标后的全部空白字符。
     */
    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }
}
