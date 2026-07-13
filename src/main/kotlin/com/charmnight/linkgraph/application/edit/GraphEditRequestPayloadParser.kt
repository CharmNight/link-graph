package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphEdgeEditInput
import com.charmnight.linkgraph.application.model.GraphNodeEditInput
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType

/**
 * 图谱编辑请求负载解析器。
 *
 * 将前端传入的树形数据结构解析为领域内的编辑请求模型，
 * 内部为不同节点/边类型提供细粒度的字段校验与默认值处理。
 *
 * 解析失败不再抛出异常，而是返回 [GraphEditRequestParseResult]：
 * - 解析成功：(request=GraphEditRequest, issues=[])
 * - 解析失败：(request=null, issues=[GraphEditIssue(...)])
 *
 * 上层（EditGraphTool / BridgeCommandParser）可直接用 issues 列表构造结构化拒绝响应，
 * 不再依赖 runCatching 捕获异常。
 */
object GraphEditRequestPayloadParser {
    /** 单个图编辑请求序列化后的最大字符数，前端 bridge 与 agent 工具入口共用。 */
    const val MAX_SERIALIZED_PAYLOAD_CHARS: Int = 512 * 1024
    /** 单个图编辑请求允许携带的最大操作数，避免小字段大数组绕过总字符上限后冲击后续流程。 */
    const val MAX_OPERATIONS: Int = 512

    /**
     * 解析图谱编辑请求的根负载。
     *
     * 任一字段畸形都会被包装为 [GraphEditIssue] 并以 [GraphEditRequestParseResult] 返回，
     * 不再向上抛 IllegalStateException。
     */
    fun parse(root: Map<*, *>): GraphEditRequestParseResult = try {
        GraphEditRequestParseResult(request = buildRequest(root), issues = emptyList())
    } catch (failure: GraphEditPayloadParseFail) {
        GraphEditRequestParseResult(request = null, issues = listOf(failure.issue))
    }

    /**
     * 单次构造请求对象；任何字段缺失/类型错/枚举拼写错都会抛 [GraphEditPayloadParseFail]。
     * 调用方包在 try/catch 里即可把失败转为 issue。
     */
    private fun buildRequest(root: Map<*, *>): GraphEditRequest {
        requireField(root.containsKey("sceneId"), "sceneId is required")
        val sceneId = root.enum<GraphSceneId>("sceneId")
        requireField(
            (root["baseWorkspaceRevision"] as? Number)?.toLong() != null,
            "graph edit request baseWorkspaceRevision is required",
        )
        val baseWorkspaceRevision = (root["baseWorkspaceRevision"] as? Number)?.toLong()
            ?: fail("graph edit request baseWorkspaceRevision is required")
        val source = root.enum<GraphEditRequestSource>("source")
        val rawOperations = (root["operations"] as? List<*>).orEmpty()
        requireField(
            rawOperations.size <= MAX_OPERATIONS,
            "graph edit request operations exceed limit: ${rawOperations.size} > $MAX_OPERATIONS",
        )
        val operations = rawOperations.mapIndexed { index, raw ->
            parseOperation(raw as? Map<*, *>, index)
        }
        return GraphEditRequest(
            sceneId = sceneId,
            baseWorkspaceRevision = baseWorkspaceRevision,
            operations = operations,
            source = source,
        )
    }

    /**
     * 解析单个编辑操作。
     */
    private fun parseOperation(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEditOperation {
        raw ?: fail("graph edit request operation[$index] must be an object", index)
        return when (val type = raw.requiredString("type", "graph edit request operation[$index].type")) {
            "UPSERT_NODE" -> GraphEditOperation.UpsertNode(parseGraphNode(raw["node"] as? Map<*, *>, index))
            "REMOVE_NODE" -> GraphEditOperation.RemoveNode(
                raw.requiredString("nodeId", "graph edit request operation[$index].nodeId"),
            )
            "UPSERT_EDGE" -> GraphEditOperation.UpsertEdge(parseGraphEdge(raw["edge"] as? Map<*, *>, index))
            "REMOVE_EDGE" -> GraphEditOperation.RemoveEdge(
                raw.requiredString("edgeId", "graph edit request operation[$index].edgeId"),
            )
            else -> fail("unsupported graph edit request operation[$index].type: $type", index)
        }
    }

    /**
     * 解析图谱节点。
     */
    private fun parseGraphNode(
        raw: Map<*, *>?,
        index: Int,
    ): GraphNodeEditInput {
        raw ?: fail("graph edit request operation[$index].node is required", index)
        raw.requireOnlyFields(NODE_EDIT_FIELDS, "node", index)
        return GraphNodeEditInput(
            id = raw.requiredString("id", "graph edit request operation[$index].node.id"),
            type = raw.enum("type"),
            title = (raw["title"] as? String)?.takeIf(String::isNotBlank)
                ?: (raw["label"] as? String)?.takeIf(String::isNotBlank)
                ?: raw.requiredString("id", "graph edit request operation[$index].node.id"),
            inputs = raw.stringList("inputs"),
            outputs = raw.stringList("outputs"),
            doc = raw["doc"] as? String,
            metadata = raw.stringMap("metadata"),
        )
    }

    /**
     * 解析图谱边。
     */
    private fun parseGraphEdge(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEdgeEditInput {
        raw ?: fail("graph edit request operation[$index].edge is required", index)
        raw.requireOnlyFields(EDGE_EDIT_FIELDS, "edge", index)
        return GraphEdgeEditInput(
            id = raw.requiredString("id", "graph edit request operation[$index].edge.id"),
            type = raw.enum("type"),
            fromNodeId = raw.requiredString("fromNodeId", "graph edit request operation[$index].edge.fromNodeId"),
            toNodeId = raw.requiredString("toNodeId", "graph edit request operation[$index].edge.toNodeId"),
            label = raw["label"] as? String,
            metadata = raw.stringMap("metadata"),
        )
    }

    private fun issue(message: String, operationIndex: Int? = null): GraphEditIssue = GraphEditIssue(
        code = GraphEditIssueCode.INVALID_PAYLOAD_FIELD,
        message = message,
        operationIndex = operationIndex,
        retryable = false,
    )

    private fun fail(message: String, operationIndex: Int? = null): Nothing {
        throw GraphEditPayloadParseFail(issue(message, operationIndex))
    }

    private fun requireField(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    /**
     * 读取必填字符串字段。
     */
    private fun Map<*, *>.requiredString(
        key: String,
        description: String,
    ): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: fail("$description is required")

    /**
     * 将指定字段解析为非空字符串列表，自动忽略空白项。
     */
    private fun Map<*, *>.stringList(key: String): List<String> {
        return (this[key] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? String)?.takeIf(String::isNotBlank)
        }
    }

    /**
     * 将指定字段解析为字符串键值映射。
     */
    private fun Map<*, *>.stringMap(key: String): Map<String, String> {
        return ((this[key] as? Map<*, *>).orEmpty()).mapNotNull { (rawKey, rawValue) ->
            val mapKey = rawKey as? String ?: return@mapNotNull null
            val mapValue = rawValue as? String ?: return@mapNotNull null
            mapKey to mapValue
        }.toMap()
    }

    /**
     * 读取必填的枚举字段，依据枚举名匹配，不匹配时抛错。
     */
    private inline fun <reified T : Enum<T>> Map<*, *>.enum(key: String): T {
        val raw = requiredString(key, key)
        return enumValues<T>().firstOrNull { it.name == raw } ?: fail("$key has unsupported value: $raw")
    }

    /** 拒绝边界 DTO 未声明的字段，避免调用方误以为信任字段会生效。 */
    private fun Map<*, *>.requireOnlyFields(
        allowed: Set<String>,
        subject: String,
        operationIndex: Int,
    ) {
        val unsupported = keys.filterIsInstance<String>().filterNot(allowed::contains).sorted()
        if (unsupported.isNotEmpty()) {
            fail("graph edit request operation[$operationIndex].$subject has unsupported fields: ${unsupported.joinToString()}", operationIndex)
        }
    }

    private val NODE_EDIT_FIELDS = setOf("id", "type", "title", "label", "inputs", "outputs", "doc", "metadata")
    private val EDGE_EDIT_FIELDS = setOf("id", "type", "fromNodeId", "toNodeId", "label", "metadata")
}

/**
 * 解析失败载体：内部辅助函数抛出后被 [GraphEditRequestPayloadParser.parse] 捕获并转成 issue。
 */
private class GraphEditPayloadParseFail(val issue: GraphEditIssue) : RuntimeException(issue.message)
