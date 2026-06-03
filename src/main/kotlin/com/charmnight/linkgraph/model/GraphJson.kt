package com.charmnight.linkgraph.model

import com.charmnight.linkgraph.json.JsonCodec

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
        return JsonCodec.toJson(root)
    }

    /**
     * 从 JSON 字符串解析出图文档。
     */
    fun fromJson(json: String): GraphDocument {
        // 根节点必须是 JSON 对象，否则视为非法图文档格式。
        val root = JsonCodec.parseObject(json, rootDescription = "Graph JSON root")

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

}
