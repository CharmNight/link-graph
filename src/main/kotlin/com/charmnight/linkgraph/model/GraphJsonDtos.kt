package com.charmnight.linkgraph.model

/**
 * GraphJson 序列化 DTO 集合（P2-6 替代之前的 untyped map payload）。
 *
 * 字段名与原 `linkedMapOf("key" to value)` 的 key 一一对应，Gson 反射序列化保证
 * 字段顺序与原输出完全一致（serializeNulls 已开），不破坏磁盘持久化格式。
 *
 * 仅用于 GraphJson.toJson 内部，外部不直接访问。
 */
internal data class GraphEvidenceJsonDto(
    val source: String,
    val detail: String?,
)

internal data class GraphUncertaintyJsonDto(
    val reason: String,
    val confidence: Double?,
)

internal data class GraphDiffEntryJsonDto(
    val elementKind: String,
    val elementId: String,
    val status: String,
    val counterpartId: String?,
    val fields: List<String>,
    val message: String?,
)

internal data class GraphDiffJsonDto(
    val status: String,
    val fields: List<String>,
    val counterpartId: String?,
    val message: String?,
    val summary: String?,
    val entries: List<GraphDiffEntryJsonDto>,
)

internal data class GraphNodeJsonDto(
    val id: String,
    val type: String,
    val title: String,
    val location: String?,
    val signature: String?,
    val inputs: List<String>,
    val outputs: List<String>,
    val doc: String?,
    val sourceKind: String?,
    val status: String?,
    val bindingStatus: String,
    val certainty: String,
    val diff: GraphDiffJsonDto,
    val evidence: List<GraphEvidenceJsonDto>,
    val uncertainty: GraphUncertaintyJsonDto?,
    val metadata: Map<String, String>,
    val sourceTag: String,
)

internal data class GraphEdgeJsonDto(
    val id: String,
    val type: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String?,
    val certainty: String,
    val bindingStatus: String,
    val status: String?,
    val diff: GraphDiffJsonDto,
    val evidence: List<GraphEvidenceJsonDto>,
    val uncertainty: GraphUncertaintyJsonDto?,
    val metadata: Map<String, String>,
    val sourceTag: String,
)

internal data class GraphPatchOperationJsonDto(
    val id: String,
    val action: String,
    val elementKind: String,
    val elementId: String,
    val title: String?,
    val summary: String?,
    val node: GraphNodeJsonDto?,
    val edge: GraphEdgeJsonDto?,
    val metadata: Map<String, String>,
)

internal data class GraphPatchJsonDto(
    val summary: String?,
    val operations: List<GraphPatchOperationJsonDto>,
    val addedNodeIds: List<String>,
    val removedNodeIds: List<String>,
    val addedEdgeIds: List<String>,
    val removedEdgeIds: List<String>,
)

internal data class GraphDocumentJsonDto(
    val nodes: List<GraphNodeJsonDto>,
    val edges: List<GraphEdgeJsonDto>,
    val patch: GraphPatchJsonDto?,
)
