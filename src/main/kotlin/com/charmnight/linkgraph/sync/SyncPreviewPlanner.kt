package com.charmnight.linkgraph.sync

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 定义同步预览项的风险等级。
 */
enum class SyncPreviewRisk {
    /** 表示低风险改动。 */
    LOW,
    /** 表示中风险改动。 */
    MEDIUM,
    /** 表示高风险改动。 */
    HIGH,
}

/**
 * 表示一个可展示给用户的同步预览建议项。
 */
data class SyncPreviewItem(
    /** 保存预览项标识。 */
    val id: String,
    /** 保存预览项标题。 */
    val title: String,
    /** 保存预览项描述。 */
    val description: String,
    /** 保存风险等级。 */
    val risk: SyncPreviewRisk,
)

/**
 * 根据 diff 结果给出“下一步可能怎么改代码”的预览项。
 * 这里先做规则化摘要，后续接入 LLM 时可以把这些项继续细化成 patch 草案。
 */
class SyncPreviewPlanner {
    /**
     * 根据图和差异结果生成同步预览项列表。
     */
    fun plan(
        graph: GraphDocument,
        diff: GraphDiff,
    ): List<SyncPreviewItem> {
        // 先建立节点和边索引，便于差异条目快速回查实体对象。
        val nodesById = graph.nodes.associateBy { it.id }
        val edgesById = graph.edges.associateBy { it.id }
        return diff.entries.mapNotNull { entry ->
            when (entry.elementKind) {
                GraphDiffElementKind.NODE -> nodesById[entry.elementId]?.let { buildNodeItem(it, entry) }
                GraphDiffElementKind.EDGE -> edgesById[entry.elementId]?.let { edge ->
                    buildEdgeItem(edge, nodesById, entry)
                }
            }
        }
    }

    /**
     * 为节点差异构建预览项。
     */
    private fun buildNodeItem(
        node: GraphNode,
        entry: GraphDiffEntry,
    ): SyncPreviewItem? {
        return when (entry.status) {
            DiffStatus.MATCHED -> null
            DiffStatus.ONLY_IN_MERMAID -> SyncPreviewItem(
                id = entry.elementId,
                title = "新增 ${node.title}",
                description = "根据 Mermaid 设计新增${nodeTypeLabel(node.type)}节点 ${node.title}。",
                risk = riskForMissingNode(node.type),
            )

            DiffStatus.ONLY_IN_CODE -> SyncPreviewItem(
                id = entry.elementId,
                title = "审查 ${node.title}",
                description = "代码中存在 ${node.title}，但 Mermaid 中没有。请确认是保留、补充说明，还是删除。",
                risk = SyncPreviewRisk.HIGH,
            )

            DiffStatus.MODIFIED -> SyncPreviewItem(
                id = entry.elementId,
                title = "对齐 ${node.title}",
                description = modifiedDescription(node.title, entry),
                risk = SyncPreviewRisk.MEDIUM,
            )
        }
    }

    /**
     * 为边差异构建预览项。
     */
    private fun buildEdgeItem(
        edge: GraphEdge,
        nodesById: Map<String, GraphNode>,
        entry: GraphDiffEntry,
    ): SyncPreviewItem? {
        // 优先使用节点标题，缺失时回退到节点标识。
        val fromTitle = nodesById[edge.fromNodeId]?.title ?: edge.fromNodeId
        val toTitle = nodesById[edge.toNodeId]?.title ?: edge.toNodeId
        return when (entry.status) {
            DiffStatus.MATCHED -> null
            DiffStatus.ONLY_IN_MERMAID -> SyncPreviewItem(
                id = entry.elementId,
                title = "补充 $fromTitle -> $toTitle",
                description = "补充从 $fromTitle 到 $toTitle 的${edgeTypeLabel(edge.type)}。",
                risk = SyncPreviewRisk.MEDIUM,
            )

            DiffStatus.ONLY_IN_CODE -> SyncPreviewItem(
                id = entry.elementId,
                title = "审查 $fromTitle -> $toTitle",
                description = "代码中存在从 $fromTitle 到 $toTitle 的${edgeTypeLabel(edge.type)}，但 Mermaid 中没有。",
                risk = SyncPreviewRisk.HIGH,
            )

            DiffStatus.MODIFIED -> SyncPreviewItem(
                id = entry.elementId,
                title = "对齐 $fromTitle -> $toTitle",
                description = modifiedDescription(edgeTypeLabel(edge.type), entry),
                risk = SyncPreviewRisk.MEDIUM,
            )
        }
    }

    /**
     * 生成“字段已变更”场景下的描述文案。
     */
    private fun modifiedDescription(
        subject: String,
        entry: GraphDiffEntry,
    ): String {
        if (entry.fields.isEmpty()) {
            return "请更新 $subject，使其与 Mermaid 设计保持一致。"
        }
        return "请更新 $subject，使其与 Mermaid 字段保持一致：${entry.fields.joinToString("、")}。"
    }

    /**
     * 把节点类型转换为中文标签。
     */
    private fun nodeTypeLabel(type: NodeType): String {
        return when (type) {
            NodeType.METHOD -> "方法"
            NodeType.FLOW_SCOPE -> "流程作用域"
            NodeType.FLOW_ACTION -> "关键动作"
            NodeType.TERMINAL -> "终止节点"
            NodeType.MERGE -> "汇合节点"
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
            NodeType.UNCERTAIN_LINK -> "不确定链路"
        }
    }

    /**
     * 把边类型转换为中文标签。
     */
    private fun edgeTypeLabel(type: com.charmnight.linkgraph.model.EdgeType): String {
        return when (type) {
            com.charmnight.linkgraph.model.EdgeType.CALL -> "调用连线"
            com.charmnight.linkgraph.model.EdgeType.CONTAINS_FLOW -> "流程包含连线"
            com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW -> "控制流连线"
            com.charmnight.linkgraph.model.EdgeType.IMPLEMENTS -> "实现连线"
            com.charmnight.linkgraph.model.EdgeType.EXTENDS -> "继承连线"
            com.charmnight.linkgraph.model.EdgeType.USES_TYPE -> "类型依赖连线"
            com.charmnight.linkgraph.model.EdgeType.INJECT -> "注入连线"
            com.charmnight.linkgraph.model.EdgeType.ROUTES_TO -> "路由连线"
            com.charmnight.linkgraph.model.EdgeType.MAPS_TO_SQL -> "SQL 映射连线"
            com.charmnight.linkgraph.model.EdgeType.PUBLISHES_TO -> "发布连线"
            com.charmnight.linkgraph.model.EdgeType.CONSUMES_FROM -> "消费连线"
            com.charmnight.linkgraph.model.EdgeType.BINDS_CONFIG -> "配置绑定连线"
            com.charmnight.linkgraph.model.EdgeType.LINKS_DOC -> "文档链接"
            com.charmnight.linkgraph.model.EdgeType.USES_PROXY -> "代理连线"
            com.charmnight.linkgraph.model.EdgeType.REFLECTS_TO -> "反射连线"
            com.charmnight.linkgraph.model.EdgeType.SPI_RESOLVES_TO -> "SPI 解析连线"
            com.charmnight.linkgraph.model.EdgeType.TESTS -> "测试连线"
            com.charmnight.linkgraph.model.EdgeType.GENERATES -> "生成连线"
        }
    }

    /**
     * 根据节点类型估算缺失节点的风险等级。
     */
    private fun riskForMissingNode(type: NodeType): SyncPreviewRisk {
        return when (type) {
            NodeType.CLASS,
            NodeType.MODULE,
            NodeType.PACKAGE,
            NodeType.INTERFACE,
            NodeType.ENUM,
            NodeType.ANNOTATION,
            NodeType.RECORD,
            NodeType.OBJECT,
            NodeType.EXTERNAL_CLASS,
            NodeType.LIBRARY,
            NodeType.SERVICE,
            NodeType.LAYER,
            NodeType.RESOURCE,
            NodeType.FLOW_SCOPE,
            NodeType.FLOW_ACTION,
            NodeType.TERMINAL,
            NodeType.MERGE,
            NodeType.CONFIG_ITEM,
            NodeType.XML_RESOURCE,
            NodeType.DOC_PAGE,
            -> SyncPreviewRisk.LOW

            NodeType.METHOD,
            NodeType.SQL,
            NodeType.HTTP_ENDPOINT,
            NodeType.FEIGN_CLIENT,
            NodeType.DUBBO_SERVICE,
            NodeType.MQ_TOPIC,
            NodeType.MQ_CONSUMER,
            NodeType.UNCERTAIN_LINK,
            -> SyncPreviewRisk.MEDIUM
        }
    }
}
