package com.charmnight.linkgraph.model

/**
 * 定义图补丁支持的操作类型。
 */
enum class GraphPatchAction {
    /** 表示新增节点。 */
    ADD_NODE,
    /** 表示更新节点。 */
    UPDATE_NODE,
    /** 表示删除节点。 */
    DELETE_NODE,
    /** 表示新增边。 */
    ADD_EDGE,
    /** 表示更新边。 */
    UPDATE_EDGE,
    /** 表示删除边。 */
    DELETE_EDGE,
    /** 表示新增注解或说明信息。 */
    ADD_ANNOTATION,
    /** 表示将元素标记为不确定。 */
    MARK_UNCERTAIN,
}

/**
 * 表示一条具体的图补丁操作。
 */
data class GraphPatchOperation(
    /** 保存补丁操作自身的唯一标识。 */
    val id: String,
    /** 保存当前操作的动作类型。 */
    val action: GraphPatchAction,
    /** 保存当前操作作用于节点还是边。 */
    val elementKind: GraphDiffElementKind,
    /** 保存被操作元素的标识。 */
    val elementId: String,
    /** 保存操作标题。 */
    val title: String? = null,
    /** 保存操作摘要说明。 */
    val summary: String? = null,
    /** 保存节点快照，当操作目标为节点时使用。 */
    val node: GraphNode? = null,
    /** 保存边快照，当操作目标为边时使用。 */
    val edge: GraphEdge? = null,
    /** 保存操作附带的扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 表示一组可应用到图文档上的补丁集合。
 */
data class GraphPatch(
    /** 保存补丁整体摘要。 */
    val summary: String? = null,
    /** 保存补丁包含的操作列表。 */
    val operations: List<GraphPatchOperation> = emptyList(),
    /** 保存新增节点标识列表。 */
    val addedNodeIds: List<String> = emptyList(),
    /** 保存删除节点标识列表。 */
    val removedNodeIds: List<String> = emptyList(),
    /** 保存新增边标识列表。 */
    val addedEdgeIds: List<String> = emptyList(),
    /** 保存删除边标识列表。 */
    val removedEdgeIds: List<String> = emptyList(),
)
