package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import java.util.ArrayDeque

/**
 * 约束版 Mermaid 导入器。
 * V1 只接受项目定义的节点/边格式，并把常用核心字段映射回 GraphNode，供 diff 与 UI 直接消费。
 */
class MermaidImporter(
    /** 负责在导入完成后补齐节点绑定状态。 */
    private val bindingService: MermaidBindingService = MermaidBindingService(),
) {
    /** 解析 Mermaid 文本并恢复为内部图模型。 */
    fun import(mermaid: String): MermaidParseResult {
        /** 导入过程中累计的语法和结构问题。 */
        val issues = mutableListOf<MermaidIssue>()
        /** 以稳定节点 ID 为键保存的节点集合。 */
        val nodesById = linkedMapOf<String, GraphNode>()
        /** 边定义在节点解析完成前的中间表示。 */
        val rawEdges = mutableListOf<RawEdge>()
        /** Mermaid 节点别名到真实节点 ID 的映射。 */
        val aliasToNodeId = linkedMapOf<String, String>()
        /** 节点别名对应的注释元数据。 */
        val commentMetadataByAlias = linkedMapOf<String, Map<String, String>>()
        /** 边起止别名对应的注释元数据队列。 */
        val edgeMetadataByAliasPair = linkedMapOf<Pair<String, String>, ArrayDeque<Map<String, String>>>()
        /** 是否已经遇到 Mermaid 头声明。 */
        var sawHeader = false

        mermaid.lineSequence().forEachIndexed { index, rawLine ->
            /** 当前处理的 Mermaid 行号。 */
            val lineNumber = index + 1
            /** 去掉首尾空白后的当前行内容。 */
            val line = rawLine.trim()
            if (line.isBlank()) {
                return@forEachIndexed
            }
            if (line.startsWith("%%")) {
                parseNodeComment(line)?.let { (alias, metadata) ->
                    commentMetadataByAlias[alias] = metadata
                }
                parseEdgeComment(line)?.let { (aliasPair, metadata) ->
                    val queue = edgeMetadataByAliasPair.getOrPut(aliasPair) { ArrayDeque() }
                    queue.addLast(metadata)
                }
                return@forEachIndexed
            }
            if (!sawHeader) {
                if (isGraphHeader(line)) {
                    sawHeader = true
                    return@forEachIndexed
                }
                issues += MermaidIssue(
                    category = MermaidIssue.Category.SYNTAX,
                    code = "missing-graph-header",
                    message = "Mermaid 文本必须以 graph 或 flowchart 头声明开头。",
                    line = lineNumber,
                )
                sawHeader = true
            }

            if (line.startsWith("subgraph ") || line == "end") {
                return@forEachIndexed
            }

            val nodeMatch = NODE_PATTERN.matchEntire(line)
            if (nodeMatch != null) {
                val alias = nodeMatch.groupValues[1]
                val node = parseNode(
                    alias = alias,
                    body = nodeMatch.groupValues[2],
                    commentMetadata = commentMetadataByAlias[alias].orEmpty(),
                    line = lineNumber,
                    issues = issues,
                )
                if (nodesById.containsKey(node.id)) {
                    issues += MermaidIssue(
                        category = MermaidIssue.Category.STRUCTURE,
                        code = "duplicate-node-id",
                        message = "节点 ID '${node.id}' 重复。",
                        line = lineNumber,
                        nodeId = node.id,
                    )
                } else {
                    aliasToNodeId[alias] = node.id
                    nodesById[node.id] = node
                }
                return@forEachIndexed
            }

            parseEdgeLine(line, lineNumber, issues, edgeMetadataByAliasPair)?.let { rawEdge ->
                rawEdges += rawEdge
                return@forEachIndexed
            }

            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "unsupported-line",
                message = "暂不支持的 Mermaid 行：'$line'。",
                line = lineNumber,
            )
        }

        /** 结合别名映射恢复出的正式边集合。 */
        val edges = rawEdges.map { rawEdge ->
            /** 边起点对应的真实节点 ID。 */
            val fromNodeId = aliasToNodeId[rawEdge.fromAlias] ?: rawEdge.fromAlias
            /** 边终点对应的真实节点 ID。 */
            val toNodeId = aliasToNodeId[rawEdge.toAlias] ?: rawEdge.toAlias
            GraphEdge(
                id = GraphEdge.stableId(rawEdge.type, fromNodeId, toNodeId),
                type = rawEdge.type,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                label = rawEdge.label,
                metadata = rawEdge.metadata,
            )
        }

        for (edge in edges) {
            ensureNodeExists(edge.fromNodeId, nodesById, issues)
            ensureNodeExists(edge.toNodeId, nodesById, issues)
        }

        val document = bindingService.apply(
            GraphDocument(
                nodes = nodesById.values.toList(),
                edges = edges.distinctBy { it.id },
            ),
        )
        return MermaidParseResult(document = document, issues = issues)
    }

    /** 解析单个节点定义，并恢复核心属性与 metadata。 */
    private fun parseNode(
        alias: String,
        body: String,
        commentMetadata: Map<String, String>,
        line: Int,
        issues: MutableList<MermaidIssue>,
    ): GraphNode {
        /** 节点解析过程中累积的元数据。 */
        val metadata = linkedMapOf<String, String>()
        metadata.putAll(commentMetadata)

        /** 解析出的节点类型名称。 */
        val typeName = metadata["nodeType"]
        /** 解析出的节点标题。 */
        val resolvedTitle = metadata["title"]
        if (typeName.isNullOrBlank() || resolvedTitle.isNullOrBlank()) {
            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "invalid-node-body",
                message = "节点 '$alias' 必须通过 LG_NODE 注释提供 nodeType/title。",
                line = line,
                nodeId = alias,
            )
            return GraphNode(id = alias, type = NodeType.UNCERTAIN_LINK, title = alias)
        }

        /** 解析出的枚举节点类型。 */
        val type = NodeType.entries.firstOrNull { it.name == typeName }
        if (type == null) {
            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "unknown-node-type",
                message = "节点 '$alias' 的类型 '$typeName' 未知。",
                line = line,
                nodeId = alias,
            )
        }

        // 这些字段会参与属性编辑和 diff，对前端来说不能只放在 metadata 里。
        /** 优先使用注释中的稳定节点 ID。 */
        val actualId = metadata["nodeId"] ?: alias
        /** 节点方法签名。 */
        val signature = metadata["signature"]
        /** 节点源码位置。 */
        val location = metadata["location"]
        /** 输入参数列表。 */
        val inputs = parseAttributeList(metadata["inputs"])
        /** 输出参数列表。 */
        val outputs = parseAttributeList(metadata["outputs"])
        /** 节点文档说明。 */
        val doc = metadata["doc"]

        return GraphNode(
            id = actualId,
            type = type ?: NodeType.UNCERTAIN_LINK,
            title = resolvedTitle.ifBlank { actualId },
            location = location,
            signature = signature,
            inputs = inputs,
            outputs = outputs,
            doc = doc,
            metadata = metadata
                .filterKeys { key -> key !in CORE_NODE_ATTRIBUTES }
                .toMap(linkedMapOf()),
        )
    }

    /** 解析节点元数据注释。 */
    private fun parseNodeComment(line: String): Pair<String, Map<String, String>>? {
        if (!line.startsWith(NODE_COMMENT_PREFIX)) {
            return null
        }
        /** 去掉前缀后的节点注释正文。 */
        val content = line.removePrefix(NODE_COMMENT_PREFIX)
        /** 按竖线切开的节点注释片段。 */
        val parts = content.split('|').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return null
        }
        /** 节点别名。 */
        val alias = parts.first()
        /** 节点注释中恢复出的元数据。 */
        val metadata = linkedMapOf<String, String>()
        parts.drop(1).forEach { segment ->
            /** 当前片段中等号的位置。 */
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0 || delimiterIndex >= segment.length - 1) {
                return@forEach
            }
            /** 元数据键。 */
            val key = segment.substring(0, delimiterIndex).trim()
            /** Mermaid 编码解码后的元数据值。 */
            val value = MermaidMetadataCodec.decode(segment.substring(delimiterIndex + 1).trim())
            metadata[key] = value
        }
        return alias to metadata
    }

    /** 解析边元数据注释。 */
    private fun parseEdgeComment(line: String): Pair<Pair<String, String>, Map<String, String>>? {
        if (!line.startsWith(EDGE_COMMENT_PREFIX)) {
            return null
        }
        /** 去掉前缀后的边注释正文。 */
        val content = line.removePrefix(EDGE_COMMENT_PREFIX)
        /** 按竖线切开的边注释片段。 */
        val parts = content.split('|').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return null
        }
        /** 边起点别名。 */
        val fromAlias = parts.first()
        /** 边注释中恢复出的元数据。 */
        val metadata = linkedMapOf<String, String>()
        parts.drop(1).forEach { segment ->
            /** 当前片段中等号的位置。 */
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0 || delimiterIndex >= segment.length - 1) {
                return@forEach
            }
            /** 元数据键。 */
            val key = segment.substring(0, delimiterIndex).trim()
            /** Mermaid 编码解码后的元数据值。 */
            val value = MermaidMetadataCodec.decode(segment.substring(delimiterIndex + 1).trim())
            metadata[key] = value
        }
        /** 边终点别名。 */
        val toAlias = metadata["to"] ?: return null
        return (fromAlias to toAlias) to metadata
    }

    /** 解析单条边定义，兼容类型边和可读标签边。 */
    private fun parseEdgeLine(
        line: String,
        lineNumber: Int,
        issues: MutableList<MermaidIssue>,
        edgeMetadataByAliasPair: Map<Pair<String, String>, ArrayDeque<Map<String, String>>>,
    ): RawEdge? {
        /** 严格类型边格式的匹配结果。 */
        val typedEdgeMatch = EDGE_PATTERN.matchEntire(line)
        if (typedEdgeMatch != null) {
            /** 边起点别名。 */
            val fromAlias = typedEdgeMatch.groupValues[1]
            /** 边类型原始文本。 */
            val edgeTypeRaw = typedEdgeMatch.groupValues[2]
            /** 边终点别名。 */
            val toAlias = typedEdgeMatch.groupValues[3]
            /** 解析出的边类型枚举。 */
            val edgeType = EdgeType.entries.firstOrNull { it.name == edgeTypeRaw }
            if (edgeType == null) {
                issues += MermaidIssue(
                    category = MermaidIssue.Category.SYNTAX,
                    code = "unknown-edge-type",
                    message = "未知边类型 '$edgeTypeRaw'。",
                    line = lineNumber,
                )
                return null
            }
            /** 当前别名对对应的元数据队列。 */
            val metadataQueue = edgeMetadataByAliasPair[fromAlias to toAlias]
            /** 取出并清洗后的边元数据。 */
            val metadata = if (metadataQueue.isNullOrEmpty()) {
                emptyMap()
            } else {
                edgeMetadataByAliasPair[fromAlias to toAlias]?.removeFirst()
                    ?.filterKeys { key -> key !in CORE_EDGE_ATTRIBUTES }
                    .orEmpty()
            }
            return RawEdge(
                fromAlias = fromAlias,
                type = edgeType,
                toAlias = toAlias,
                label = null,
                metadata = metadata,
            )
        }

        /** 可读标签边格式的匹配结果。 */
        val readableEdgeMatch = READABLE_EDGE_PATTERN.matchEntire(line) ?: return null
        /** 边起点别名。 */
        val fromAlias = readableEdgeMatch.groupValues[1]
        /** Mermaid 里展示给用户看的标签。 */
        val visibleLabel = readableEdgeMatch.groupValues[2].trim()
        /** 边终点别名。 */
        val toAlias = readableEdgeMatch.groupValues[3]
        /** 当前别名对对应的元数据队列。 */
        val metadataQueue = edgeMetadataByAliasPair[fromAlias to toAlias]
        /** 取出的完整边元数据。 */
        val metadata = if (metadataQueue.isNullOrEmpty()) null else metadataQueue.removeFirst()
        /** 注释里记录的边类型字符串。 */
        val edgeTypeRaw = metadata?.get("edgeType")
        /** 恢复出的边类型枚举。 */
        val edgeType = edgeTypeRaw?.let { raw -> EdgeType.entries.firstOrNull { it.name == raw } }
        if (edgeType == null) {
            issues += MermaidIssue(
                category = MermaidIssue.Category.SYNTAX,
                code = "missing-edge-type-metadata",
                message = "可读边 '$line' 缺少 LG_EDGE 注释中的 edgeType 元数据。",
                line = lineNumber,
                )
                return null
        }
        /** 优先恢复原始标签，否则保留用户可见标签。 */
        val restoredLabel = metadata["label"]?.takeIf { it.isNotBlank() }
            ?: visibleLabel.takeUnless { it == readableEdgeLabel(edgeType) }
        return RawEdge(
            fromAlias = fromAlias,
            type = edgeType,
            toAlias = toAlias,
            label = restoredLabel,
            metadata = metadata
                .filterKeys { key -> key !in CORE_EDGE_ATTRIBUTES }
                .toMap(linkedMapOf()),
        )
    }

    /** 如果边引用了未声明节点，则补一个占位节点并记录问题。 */
    private fun ensureNodeExists(
        nodeId: String,
        nodesById: MutableMap<String, GraphNode>,
        issues: MutableList<MermaidIssue>,
    ) {
        if (nodesById.containsKey(nodeId)) {
            return
        }
        issues += MermaidIssue(
            category = MermaidIssue.Category.STRUCTURE,
            code = "edge-references-missing-node",
            message = "边引用了未声明节点 '$nodeId'。",
            nodeId = nodeId,
        )
        nodesById[nodeId] = GraphNode(
            id = nodeId,
            type = NodeType.UNCERTAIN_LINK,
            title = nodeId,
            metadata = mapOf(MermaidBindingService.BINDING_KEY to "UNMATCHED"),
        )
    }

    /** 把逗号分隔的属性值解析成字符串列表。 */
    private fun parseAttributeList(value: String?): List<String> {
        return value.orEmpty()
            .split(',')
            .map { item -> item.trim() }
            .filter { item -> item.isNotBlank() }
    }

    /** 把边类型转换为默认可读标签。 */
    private fun readableEdgeLabel(type: EdgeType): String {
        return when (type) {
            EdgeType.CALL -> "调用"
            EdgeType.CONTAINS_FLOW -> "包含流程"
            EdgeType.CONTROL_FLOW -> "控制流"
            EdgeType.IMPLEMENTS -> "实现"
            EdgeType.INJECT -> "注入"
            EdgeType.ROUTES_TO -> "路由"
            EdgeType.MAPS_TO_SQL -> "映射到 SQL"
            EdgeType.PUBLISHES_TO -> "发布"
            EdgeType.CONSUMES_FROM -> "消费"
            EdgeType.BINDS_CONFIG -> "绑定配置"
            EdgeType.LINKS_DOC -> "链接文档"
            EdgeType.USES_PROXY -> "使用代理"
            EdgeType.REFLECTS_TO -> "反射到"
            EdgeType.SPI_RESOLVES_TO -> "SPI 解析到"
            EdgeType.GENERATES -> "生成"
        }
    }

    private companion object {
        /** Mermaid 头声明匹配规则。 */
        val GRAPH_HEADER_PATTERN = Regex("""^(graph|flowchart)\s+\S+(?:\s+.*)?$""")
        /** 节点定义匹配规则。 */
        val NODE_PATTERN = Regex("""^([A-Za-z0-9:_-]+)\["([^"]*)"]$""")
        /** 严格类型边定义匹配规则。 */
        val EDGE_PATTERN = Regex("""^([A-Za-z0-9:_-]+)\s*--\s*([A-Z_]+)\s*-->\s*([A-Za-z0-9:_-]+)$""")
        /** 可读标签边定义匹配规则。 */
        val READABLE_EDGE_PATTERN = Regex("""^([A-Za-z0-9:_-]+)\s*--\s*(.+?)\s*-->\s*([A-Za-z0-9:_-]+)$""")
        /** 节点元数据注释前缀。 */
        const val NODE_COMMENT_PREFIX = "%% LG_NODE "
        /** 边元数据注释前缀。 */
        const val EDGE_COMMENT_PREFIX = "%% LG_EDGE "
        /** 需要提升为 GraphNode 核心字段的属性键。 */
        val CORE_NODE_ATTRIBUTES = setOf("nodeId", "nodeType", "title", "signature", "location", "inputs", "outputs", "doc")
        /** 需要提升为 GraphEdge 核心字段的属性键。 */
        val CORE_EDGE_ATTRIBUTES = setOf("to", "edgeType", "label")
    }

    /** 判断当前行是否为 Mermaid 图头。 */
    private fun isGraphHeader(line: String): Boolean = GRAPH_HEADER_PATTERN.matches(line)
}

private data class RawEdge(
    /** 边起点别名。 */
    val fromAlias: String,
    /** 边类型。 */
    val type: EdgeType,
    /** 边终点别名。 */
    val toAlias: String,
    /** 原始业务标签。 */
    val label: String? = null,
    /** 附加元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)
