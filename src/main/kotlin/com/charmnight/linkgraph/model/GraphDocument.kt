package com.charmnight.linkgraph.model

/**
 * 表示一个完整的链路图文档。
 */
data class GraphDocument(
    /** 保存图中的节点集合。 */
    val nodes: List<GraphNode> = emptyList(),
    /** 保存图中的边集合。 */
    val edges: List<GraphEdge> = emptyList(),
    /** 保存与当前文档关联的补丁信息。 */
    val patch: GraphPatch? = null,
)
