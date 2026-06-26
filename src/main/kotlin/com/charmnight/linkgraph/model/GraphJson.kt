package com.charmnight.linkgraph.model

import com.charmnight.linkgraph.json.JsonCodec

/**
 * 负责链路图文档的 JSON 编解码。
 *
 * P2-6：序列化（toJson）走 [GraphDocumentJsonDto] 等 DTO，反序列化（fromJson）仍按 Map<*, *> 解析
 * （因为输入是动态 JSON，Map<*, *> 是合理的解析中间形态）。
 */
object GraphJson {
    /**
     * 将图文档编码为稳定排序的 JSON 字符串。
     */
    fun toJson(document: GraphDocument): String {
        // 节点和边按标识排序，保证同一图多次序列化结果稳定。
        val sortedNodes = document.nodes.sortedBy { it.id }
        val sortedEdges = document.edges.sortedBy { it.id }
        val root = GraphDocumentJsonDto(
            nodes = sortedNodes.map { nodeToDto(it) },
            edges = sortedEdges.map { edgeToDto(it) },
            patch = document.patch?.let { patchToDto(it) },
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

    /** 把节点对象转换为 JSON DTO。 */
    private fun nodeToDto(node: GraphNode): GraphNodeJsonDto = GraphNodeJsonDto(
        id = node.id,
        type = node.type.name,
        title = node.title,
        location = node.location,
        signature = node.signature,
        inputs = node.inputs,
        outputs = node.outputs,
        doc = node.doc,
        sourceKind = node.sourceKind,
        status = node.status,
        bindingStatus = node.bindingStatus.name,
        certainty = node.certainty.name,
        diff = diffToDto(node.diff),
        evidence = node.evidence.map { evidenceToDto(it) },
        uncertainty = node.uncertainty?.let { uncertaintyToDto(it) },
        metadata = node.metadata.toSortedMap(),
        sourceTag = node.sourceTag.name,
    )

    /** 把边对象转换为 JSON DTO。 */
    private fun edgeToDto(edge: GraphEdge): GraphEdgeJsonDto = GraphEdgeJsonDto(
        id = edge.id,
        type = edge.type.name,
        fromNodeId = edge.fromNodeId,
        toNodeId = edge.toNodeId,
        label = edge.label,
        certainty = edge.certainty.name,
        bindingStatus = edge.bindingStatus.name,
        status = edge.status,
        diff = diffToDto(edge.diff),
        evidence = edge.evidence.map { evidenceToDto(it) },
        uncertainty = edge.uncertainty?.let { uncertaintyToDto(it) },
        metadata = edge.metadata.toSortedMap(),
        sourceTag = edge.sourceTag.name,
    )

    /** 把图补丁转换为 JSON DTO。 */
    private fun patchToDto(patch: GraphPatch): GraphPatchJsonDto = GraphPatchJsonDto(
        summary = patch.summary,
        operations = patch.operations.map { operationToDto(it) },
        addedNodeIds = patch.addedNodeIds,
        removedNodeIds = patch.removedNodeIds,
        addedEdgeIds = patch.addedEdgeIds,
        removedEdgeIds = patch.removedEdgeIds,
    )

    /** 把单个补丁操作转换为 JSON DTO。 */
    private fun operationToDto(operation: GraphPatchOperation): GraphPatchOperationJsonDto = GraphPatchOperationJsonDto(
        id = operation.id,
        action = operation.action.name,
        elementKind = operation.elementKind.name,
        elementId = operation.elementId,
        title = operation.title,
        summary = operation.summary,
        node = operation.node?.let { nodeToDto(it) },
        edge = operation.edge?.let { edgeToDto(it) },
        metadata = operation.metadata.toSortedMap(),
    )

    /** 把不确定信息转换为 JSON DTO。 */
    private fun uncertaintyToDto(uncertainty: GraphUncertainty): GraphUncertaintyJsonDto = GraphUncertaintyJsonDto(
        reason = uncertainty.reason,
        confidence = uncertainty.confidence,
    )

    /** 把证据信息转换为 JSON DTO。 */
    private fun evidenceToDto(evidence: GraphEvidence): GraphEvidenceJsonDto = GraphEvidenceJsonDto(
        source = evidence.source,
        detail = evidence.detail,
    )

    /** 把差异信息转换为 JSON DTO。 */
    private fun diffToDto(diff: GraphDiff): GraphDiffJsonDto = GraphDiffJsonDto(
        status = diff.status.name,
        fields = diff.fields,
        counterpartId = diff.counterpartId,
        message = diff.message,
        summary = diff.summary,
        entries = diff.entries.map { diffEntryToDto(it) },
    )

    /** 把单条差异记录转换为 JSON DTO。 */
    private fun diffEntryToDto(entry: GraphDiffEntry): GraphDiffEntryJsonDto = GraphDiffEntryJsonDto(
        elementKind = entry.elementKind.name,
        elementId = entry.elementId,
        status = entry.status.name,
        counterpartId = entry.counterpartId,
        fields = entry.fields,
        message = entry.message,
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
