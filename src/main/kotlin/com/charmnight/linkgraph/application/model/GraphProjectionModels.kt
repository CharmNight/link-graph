package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 投影节点/边的映射种类。
 *
 * 不同种类决定了节点/边在 UI 上的可编辑性：
 * - EXACT 表示投影与规范一一对应；
 * - *_ALIAS 表示多个规范合并为一个投影；
 * - *_READONLY 表示投影仅供展示，不可编辑。
 */
enum class GraphProjectionMappingKind {
    /** 投影节点严格对应一个规范节点。 */
    EXACT,

    /** 多个规范节点合并为一个投影节点（别名）。 */
    MERGED_ALIAS,

    /** 路径别名：投影代表一条路径上的多个节点。 */
    PATH_ALIAS,

    /** 来自索引的只读节点：内容固定但布局可调。 */
    INDEXED_READONLY,

    /** 合成只读：投影器生成的虚拟节点（例如溢出摘要）。 */
    SYNTHETIC_READONLY,

    /** 溢出只读：代表被裁掉的若干节点的汇总。 */
    OVERFLOW_READONLY,
}

/** 图编辑命令种类。 */
enum class GraphEditCommandKind {
    /** 新增节点。 */
    ADD_NODE,
    /** 更新节点（标题、字段等）。 */
    UPDATE_NODE,
    /** 删除节点。 */
    DELETE_NODE,
    /** 删除节点及其整棵子树。 */
    DELETE_NODE_SUBTREE,
    /** 连接两个节点（新增边）。 */
    CONNECT_NODES,
    /** 删除边。 */
    DELETE_EDGE,
    /** 在已有边上插入新节点（断开原边，新增两条边）。 */
    INSERT_NODE_INTO_EDGE,
}

/**
 * 投影节点的映射信息。
 *
 * @property projectedNodeId 投影节点 ID
 * @property mappingKind 映射种类
 * @property canonicalNodeIds 该投影对应的规范节点 ID 列表
 * @property editableCommandKinds 允许在投影节点上执行的命令集合
 */
data class GraphProjectionNodeMapping(
    val projectedNodeId: String,
    val mappingKind: GraphProjectionMappingKind,
    val canonicalNodeIds: List<String> = emptyList(),
    val editableCommandKinds: Set<GraphEditCommandKind> = emptySet(),
)

/**
 * 投影边的映射信息。语义同 [GraphProjectionNodeMapping]，但用于边。
 *
 * @property canonicalPathNodeIds 路径别名情况下，规范路径上经过的节点 ID
 */
data class GraphProjectionEdgeMapping(
    val projectedEdgeId: String,
    val mappingKind: GraphProjectionMappingKind,
    val canonicalEdgeIds: List<String> = emptyList(),
    val canonicalPathNodeIds: List<String> = emptyList(),
    val editableCommandKinds: Set<GraphEditCommandKind> = emptySet(),
)

/**
 * 投影索引：所有节点/边映射的集合。
 *
 * 让 UI 在做编辑决策时统一查询"这个投影节点能做什么操作、对应哪些规范节点"。
 */
data class GraphProjectionIndex(
    val nodeMappings: Map<String, GraphProjectionNodeMapping> = emptyMap(),
    val edgeMappings: Map<String, GraphProjectionEdgeMapping> = emptyMap(),
) {
    /** 按节点 ID 查映射。 */
    fun nodeMapping(nodeId: String): GraphProjectionNodeMapping? = nodeMappings[nodeId]

    /** 按边 ID 查映射。 */
    fun edgeMapping(edgeId: String): GraphProjectionEdgeMapping? = edgeMappings[edgeId]

    companion object {
        /** 空索引单例；无任何投影信息时使用。 */
        val EMPTY = GraphProjectionIndex()
    }
}

/**
 * 应用层视图：把可见图、完整图与投影索引打包。
 *
 * 这是上层使用图的标准形态——任何调用方拿到本对象都可以同时访问"渲染什么"和"能编辑什么"。
 */
data class ApplicationGraphView(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)
