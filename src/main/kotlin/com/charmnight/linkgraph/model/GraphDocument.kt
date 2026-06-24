package com.charmnight.linkgraph.model

/**
 * 表示一个完整的链路图文档。
 *
 * 一份文档由节点、边与可选补丁构成：节点和边是数据主体，
 * 补丁则记录"当前这份文档相对上一版本做了哪些变更"，便于差异展示与撤销栈。
 */
data class GraphDocument(
    /** 保存图中的节点集合。顺序通常按生成顺序保留，UI 可基于此做默认排序。 */
    val nodes: List<GraphNode> = emptyList(),
    /** 保存图中的边集合。 */
    val edges: List<GraphEdge> = emptyList(),
    /** 保存与当前文档关联的补丁信息；为 null 表示这份文档没有补丁上下文。 */
    val patch: GraphPatch? = null,
)
