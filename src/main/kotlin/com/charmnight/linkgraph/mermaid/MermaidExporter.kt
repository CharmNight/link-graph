package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 把当前图模型导出为插件约定的 Mermaid 文本。
 * 导出时尽量保留节点的关键字段，保证编辑后的设计图还能被完整导回。
 */
class MermaidExporter {
    /** 把图文档导出成 Mermaid 文本。 */
    fun export(document: GraphDocument): String {
        /** 按稳定规则排序后的节点列表。 */
        val sortedNodes = sortNodesForExport(document.nodes)
        /** 节点 ID 到 Mermaid 别名的映射。 */
        val aliasByNodeId = sortedNodes.mapIndexed { index, node -> node.id to "N${index + 1}" }.toMap()
        /** 最终输出的 Mermaid 行集合。 */
        val lines = mutableListOf("graph TD")
        /** 边定义及其元数据注释行。 */
        val edgeLines = mutableListOf<String>()
        if (shouldGroupByDirection(sortedNodes)) {
            buildDirectionSubgraphs(sortedNodes, aliasByNodeId).forEach(lines::add)
        } else {
            /** 节点附带的结构化元数据注释。 */
            val metadataLines = mutableListOf<String>()
            /** 纯节点定义行。 */
            val nodeLines = mutableListOf<String>()
            sortedNodes.forEach { node ->
                /** 当前节点在 Mermaid 中使用的别名。 */
                val alias = aliasByNodeId.getValue(node.id)
                buildMetadataComment(alias, node)?.let(metadataLines::add)
                nodeLines += """$alias["${buildNodeLabel(node)}"]"""
            }
            lines += metadataLines
            lines += nodeLines
        }
        document.edges
            .sortedBy { it.id }
            .forEach { edge ->
                /** 边起点在 Mermaid 中的别名。 */
                val fromAlias = aliasByNodeId[edge.fromNodeId] ?: edge.fromNodeId
                /** 边终点在 Mermaid 中的别名。 */
                val toAlias = aliasByNodeId[edge.toNodeId] ?: edge.toNodeId
                buildEdgeMetadataComment(fromAlias, toAlias, edge)?.let(edgeLines::add)
                edgeLines += "$fromAlias -- ${buildReadableEdgeLabel(edge)} --> $toAlias"
            }
        lines += edgeLines
        return lines.joinToString("\n")
    }

    /** 判断当前节点集是否值得按上下游方向分组输出。 */
    private fun shouldGroupByDirection(nodes: List<GraphNode>): Boolean {
        return nodes.any { recognizedDirection(it) != null }
    }

    /** 按方向把节点拆成多个 Mermaid 子图。 */
    private fun buildDirectionSubgraphs(
        nodes: List<GraphNode>,
        aliasByNodeId: Map<String, String>,
    ): List<String> {
        /** 子图输出行集合。 */
        val lines = mutableListOf<String>()
        /** 方向到节点列表的分组结果。 */
        val groups = nodes.groupBy { recognizedDirection(it) ?: "OTHER" }
        DIRECTION_ORDER.forEach { direction ->
            /** 当前方向对应的节点集合。 */
            val groupNodes = groups[direction].orEmpty()
            if (groupNodes.isEmpty()) {
                return@forEach
            }
            lines += "subgraph ${directionTitle(direction)}"
            groupNodes.forEach { node ->
                /** 当前节点在 Mermaid 中使用的别名。 */
                val alias = aliasByNodeId.getValue(node.id)
                buildMetadataComment(alias, node)?.let(lines::add)
                lines += """$alias["${buildNodeLabel(node)}"]"""
            }
            lines += "end"
        }
        return lines
    }

    /** 把内部方向编码转换为用户可读标题。 */
    private fun directionTitle(direction: String): String {
        return when (direction) {
            "UPSTREAM" -> "上游"
            "CURRENT" -> "当前"
            "DOWNSTREAM" -> "下游"
            else -> "其他"
        }
    }

    /** 从节点元数据中识别布局方向。 */
    private fun recognizedDirection(node: GraphNode): String? {
        /** 节点元数据中的方向值。 */
        val value = node.metadata["layout.direction"]?.trim()
        return when (value) {
            "UPSTREAM", "CURRENT", "DOWNSTREAM" -> value
            else -> null
        }
    }

    /** 为导出结果提供稳定排序，降低 diff 噪声。 */
    private fun sortNodesForExport(nodes: List<GraphNode>): List<GraphNode> {
        return nodes.sortedWith(
            compareBy<GraphNode>(
                { directionRank(it) },
                { metadataInt(it, "layout.depth") ?: Int.MAX_VALUE },
                { metadataInt(it, GraphMetadataKeys.Ui.X) ?: Int.MAX_VALUE },
                { metadataInt(it, GraphMetadataKeys.Ui.Y) ?: Int.MAX_VALUE },
                { it.id },
            ),
        )
    }

    /** 计算节点方向的排序优先级。 */
    private fun directionRank(node: GraphNode): Int {
        return when (recognizedDirection(node)) {
            "UPSTREAM" -> 0
            "CURRENT" -> 1
            "DOWNSTREAM" -> 2
            else -> 3
        }
    }

    /** 从节点元数据里读取整数值。 */
    private fun metadataInt(
        node: GraphNode,
        key: String,
    ): Int? {
        return node.metadata[key]?.toIntOrNull()
    }

    /** 计算节点稳定 ID，避免空 ID 导致回导结果漂移。 */
    private fun stableNodeId(node: GraphNode): String {
        if (node.id.isNotBlank()) {
            return node.id
        }
        /** 参与稳定 ID 计算的业务键。 */
        val key = if (node.type == NodeType.METHOD) {
            node.signature ?: node.title
        } else {
            node.title
        }
        return com.charmnight.linkgraph.model.GraphNode.stableId(node.type, key)
    }

    /** 构建 Mermaid 节点的可见标签。 */
    private fun buildNodeLabel(node: GraphNode): String {
        return buildLabelLines(node)
            .map { line -> escapeLabel(sanitizeVisibleText(line, maxLength = 52)) }
            .joinToString("<br/>")
    }

    /** 生成节点对应的结构化注释，供回导时恢复完整属性。 */
    private fun buildMetadataComment(
        alias: String,
        node: GraphNode,
    ): String? {
        /** 按顺序输出的节点元数据片段。 */
        val segments = mutableListOf<String>()
        segments += "nodeId=${encodeMetadataValue(stableNodeId(node))}"
        segments += "nodeType=${encodeMetadataValue(node.type.name)}"
        segments += "title=${encodeMetadataValue(node.title)}"

        /** 已经提升为核心字段输出的 metadata 键集合。 */
        val emitted = mutableSetOf<String>()
        if (!node.signature.isNullOrBlank()) {
            segments += "signature=${encodeMetadataValue(node.signature)}"
            emitted += "signature"
        }
        if (!node.location.isNullOrBlank()) {
            segments += "location=${encodeMetadataValue(node.location)}"
            emitted += "location"
        }
        if (node.inputs.isNotEmpty()) {
            segments += "inputs=${encodeMetadataValue(node.inputs.joinToString(","))}"
            emitted += "inputs"
        }
        if (node.outputs.isNotEmpty()) {
            segments += "outputs=${encodeMetadataValue(node.outputs.joinToString(","))}"
            emitted += "outputs"
        }
        if (!node.doc.isNullOrBlank()) {
            segments += "doc=${encodeMetadataValue(node.doc)}"
            emitted += "doc"
        }

        node.metadata
            .toSortedMap()
            .forEach { (key, value) ->
                if (key !in emitted && key != "nodeId") {
                    segments += "$key=${encodeMetadataValue(value)}"
                }
            }
        return segments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "%% LG_NODE $alias|", separator = "|")
    }

    /** 生成边的结构化注释，保留回导所需的类型和标签。 */
    private fun buildEdgeMetadataComment(
        fromAlias: String,
        toAlias: String,
        edge: GraphEdge,
    ): String? {
        /** 按顺序输出的边元数据片段。 */
        val segments = mutableListOf<String>()
        segments += "to=${encodeMetadataValue(toAlias)}"
        segments += "edgeType=${encodeMetadataValue(edge.type.name)}"
        edge.label
            ?.takeIf { it.isNotBlank() }
            ?.let { label -> segments += "label=${encodeMetadataValue(label)}" }
        edge.metadata
            .toSortedMap()
            .forEach { (key, value) ->
                if (key != "to" && key != "edgeType" && key != "label") {
                    segments += "$key=${encodeMetadataValue(value)}"
                }
            }
        return segments
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "%% LG_EDGE $fromAlias|", separator = "|")
    }

    /** 转义 Mermaid 标签中的特殊字符。 */
    private fun escapeLabel(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ")
    }

    private fun sanitizeVisibleText(
        value: String,
        maxLength: Int,
    ): String {
        /** 处理 Mermaid 保留字符和换行后的可见文本。 */
        val sanitized = value
            .replace("-->", "-〉")
            .replace("->", "-〉")
            .replace("\\", "/")
            .replace("\"", "＂")
            .replace("[", "［")
            .replace("]", "］")
            .replace("{", "｛")
            .replace("}", "｝")
            .replace("(", "（")
            .replace(")", "）")
            .replace("<", "〈")
            .replace(">", "〉")
            .replace("|", "｜")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
            .ifBlank { "未命名" }
        return if (sanitized.length <= maxLength) {
            sanitized
        } else {
            sanitized.take(maxLength - 1).trimEnd() + "…"
        }
    }

    private fun encodeMetadataValue(value: String): String {
        return MermaidMetadataCodec.encode(value)
    }

    /**
     * 可视 label 只负责“给人看”，真正可回导的结构信息统一放在注释元数据里。
     * 这样 Mermaid 文本在导出后更容易阅读，回导时也不会依赖易变的展示文案。
     */
    private fun buildLabelLines(node: GraphNode): List<String> {
        return when (node.type) {
            NodeType.METHOD -> buildMethodLabelLines(node)
            else -> buildGenericLabelLines(node)
        }
    }

    /** 生成方法节点的标签行。 */
    private fun buildMethodLabelLines(node: GraphNode): List<String> {
        /** 方法节点显示时使用的标签行。 */
        val lines = mutableListOf<String>()
        node.doc?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotBlank() }
            ?.let(lines::add)
        extractMethodOwner(node)?.let(lines::add)
        buildMethodSignaturePreview(node)?.let(lines::add)
        if (lines.isEmpty()) {
            lines += "方法"
            lines += node.title
        }
        return lines
    }

    /** 生成非方法节点的标签行。 */
    private fun buildGenericLabelLines(node: GraphNode): List<String> {
        /** 通用节点显示时使用的标签行。 */
        val lines = mutableListOf<String>()
        node.doc?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotBlank() }
            ?.takeUnless { it == node.title }
            ?.let(lines::add)
        lines += readableTypeLabel(node.type)
        lines += node.title
        return lines
    }

    /** 把节点类型转换为可读中文标签。 */
    private fun readableTypeLabel(type: NodeType): String {
        return when (type) {
            NodeType.METHOD -> "方法"
            NodeType.FLOW_SCOPE -> "流程作用域"
            NodeType.FLOW_ACTION -> "关键动作"
            NodeType.TERMINAL -> "终止"
            NodeType.MERGE -> "汇合"
            NodeType.CLASS -> "类"
            NodeType.MODULE -> "模块"
            NodeType.PACKAGE -> "包"
            NodeType.INTERFACE -> "接口"
            NodeType.ENUM -> "枚举"
            NodeType.ANNOTATION -> "注解"
            NodeType.RECORD -> "Record"
            NodeType.OBJECT -> "Object"
            NodeType.EXTERNAL_CLASS -> "外部类"
            NodeType.LIBRARY -> "依赖库"
            NodeType.SERVICE -> "服务"
            NodeType.COMPONENT -> "组件"
            NodeType.LAYER -> "架构层"
            NodeType.RESOURCE -> "资源"
            NodeType.SQL -> "SQL"
            NodeType.HTTP_ENDPOINT -> "HTTP 接口"
            NodeType.FEIGN_CLIENT -> "Feign 客户端"
            NodeType.DUBBO_SERVICE -> "Dubbo 服务"
            NodeType.MQ_TOPIC -> "MQ 主题"
            NodeType.MQ_CONSUMER -> "MQ 消费者"
            NodeType.CONFIG_ITEM -> "配置项"
            NodeType.XML_RESOURCE -> "XML 资源"
            NodeType.DOC_PAGE -> "文档页"
            NodeType.UNCERTAIN_LINK -> "待确认链路"
        }
    }

    /** 从方法签名中提取所属类型名称。 */
    private fun extractMethodOwner(node: GraphNode): String? {
        /** 用于解析所属类名的原始签名文本。 */
        val source = node.signature?.takeIf { it.isNotBlank() } ?: node.title
        /** 方法参数起始位置。 */
        val methodBoundary = source.indexOf('(').takeIf { it >= 0 } ?: source.length
        /** 方法名前最后一个点号的位置。 */
        val ownerDotIndex = source.lastIndexOf('.', methodBoundary)
        if (ownerDotIndex <= 0) {
            return null
        }
        return shortTypeName(source.substring(0, ownerDotIndex))
    }

    /** 构建方法签名的紧凑预览文案。 */
    private fun buildMethodSignaturePreview(node: GraphNode): String? {
        /** 优先使用显式签名，其次回退到标题。 */
        val source = node.signature?.takeIf { it.isNotBlank() } ?: node.title.takeIf { it.isNotBlank() } ?: return null
        /** 参数列表左括号位置。 */
        val openParen = source.indexOf('(')
        /** 参数列表右括号位置。 */
        val closeParen = source.indexOf(')', startIndex = (openParen + 1).coerceAtLeast(0))
        /** 方法名前最后一个点号位置。 */
        val methodDotIndex = if (openParen >= 0) {
            source.lastIndexOf('.', openParen)
        } else {
            source.lastIndexOf('.')
        }
        /** 解析出的简短方法名。 */
        val methodName = when {
            methodDotIndex >= 0 && openParen > methodDotIndex -> source.substring(methodDotIndex + 1, openParen)
            methodDotIndex >= 0 -> source.substring(methodDotIndex + 1)
            openParen > 0 -> source.substring(0, openParen)
            else -> source
        }.trim()
        if (methodName.isBlank()) {
            return null
        }

        /** 方法参数类型列表。 */
        val parameters = when {
            node.inputs.isNotEmpty() -> node.inputs
            openParen >= 0 && closeParen > openParen -> source.substring(openParen + 1, closeParen)
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
            else -> emptyList()
        }
        /** 方法返回值类型列表。 */
        val returns = when {
            node.outputs.isNotEmpty() -> node.outputs
            closeParen >= 0 && closeParen + 1 < source.length && source[closeParen + 1] == ':' -> {
                listOf(source.substring(closeParen + 2).trim()).filter(String::isNotBlank)
            }
            else -> emptyList()
        }
        /** 返回值的紧凑展示文本。 */
        val returnText = returns
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { shortTypeName(it) }
            ?: "void"
        /** 参数列表的紧凑展示文本。 */
        val inputText = parameters.joinToString(", ") { shortTypeName(it) }
        return "$returnText $methodName($inputText)"
    }

    /** 裁剪限定类名，只保留对阅读更重要的短类型名。 */
    private fun shortTypeName(value: String): String {
        return value
            .replace("java.lang.", "")
            .replace(QUALIFIED_TYPE_PATTERN, "$1")
    }

    /** 生成边的可读标签。 */
    private fun buildReadableEdgeLabel(edge: GraphEdge): String {
        return sanitizeVisibleText(
            edge.label?.takeIf { it.isNotBlank() } ?: readableEdgeLabel(edge.type),
            maxLength = 28,
        )
    }

    /** 把边类型转换为用户可读标签。 */
    private fun readableEdgeLabel(type: EdgeType): String {
        return when (type) {
            EdgeType.CALL -> "调用"
            EdgeType.CONTAINS_FLOW -> "包含流程"
            EdgeType.CONTROL_FLOW -> "控制流"
            EdgeType.IMPLEMENTS -> "实现"
            EdgeType.EXTENDS -> "继承"
            EdgeType.USES_TYPE -> "类型依赖"
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
            EdgeType.TESTS -> "测试"
            EdgeType.GENERATES -> "生成"
        }
    }

    private companion object {
        /** Mermaid 子图输出时的固定方向顺序。 */
        val DIRECTION_ORDER = listOf("UPSTREAM", "CURRENT", "DOWNSTREAM", "OTHER")
        /** 裁剪限定类型名时使用的正则。 */
        val QUALIFIED_TYPE_PATTERN = Regex("""\b(?:[a-z_]\w*\.)+([A-Z]\w*)""")
        /** 合并连续空白字符时使用的正则。 */
        val WHITESPACE_PATTERN = Regex("""\s+""")
    }
}
