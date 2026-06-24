package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssue
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
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
        val operations = (root["operations"] as? List<*>).orEmpty().mapIndexed { index, raw ->
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
    ): GraphNode {
        raw ?: fail("graph edit request operation[$index].node is required", index)
        return GraphNode(
            id = raw.requiredString("id", "graph edit request operation[$index].node.id"),
            type = raw.enum("type"),
            title = (raw["title"] as? String)?.takeIf(String::isNotBlank)
                ?: (raw["label"] as? String)?.takeIf(String::isNotBlank)
                ?: raw.requiredString("id", "graph edit request operation[$index].node.id"),
            location = raw["location"] as? String,
            signature = raw["signature"] as? String,
            inputs = raw.stringList("inputs"),
            outputs = raw.stringList("outputs"),
            doc = raw["doc"] as? String,
            sourceKind = raw["sourceKind"] as? String,
            status = raw["status"] as? String,
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            metadata = raw.stringMap("metadata"),
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
        )
    }

    /**
     * 解析图谱边。
     */
    private fun parseGraphEdge(
        raw: Map<*, *>?,
        index: Int,
    ): GraphEdge {
        raw ?: fail("graph edit request operation[$index].edge is required", index)
        return GraphEdge(
            id = raw.requiredString("id", "graph edit request operation[$index].edge.id"),
            type = raw.enum("type"),
            fromNodeId = raw.requiredString("fromNodeId", "graph edit request operation[$index].edge.fromNodeId"),
            toNodeId = raw.requiredString("toNodeId", "graph edit request operation[$index].edge.toNodeId"),
            label = raw["label"] as? String,
            certainty = raw.enumOrDefault("certainty", Certainty.PROVEN),
            bindingStatus = raw.enumOrDefault("bindingStatus", BindingStatus.BOUND),
            status = raw["status"] as? String,
            metadata = raw.stringMap("metadata"),
            sourceTag = raw.enumOrDefault("sourceTag", GraphSourceTag.FACT),
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

    /**
     * 读取可选的枚举字段，缺省时返回传入的默认值，无法匹配时报错。
     */
    private inline fun <reified T : Enum<T>> Map<*, *>.enumOrDefault(
        key: String,
        defaultValue: T,
    ): T {
        val raw = this[key] as? String ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == raw } ?: fail("$key has unsupported value: $raw")
    }
}

/**
 * 解析失败载体：内部辅助函数抛出后被 [GraphEditRequestPayloadParser.parse] 捕获并转成 issue。
 */
private class GraphEditPayloadParseFail(val issue: GraphEditIssue) : RuntimeException(issue.message)
