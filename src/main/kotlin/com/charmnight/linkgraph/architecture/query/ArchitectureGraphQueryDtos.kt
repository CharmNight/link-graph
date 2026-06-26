package com.charmnight.linkgraph.architecture.query

/**
 * 架构图查询 payload DTO（P2-6 替代之前的 `Map<String, Any?>`）。
 *
 * 字段名与原 mapOf 的 key 一一对应，Gson 反射序列化保证字段顺序一致。
 * 仅用于 ArchitectureGraphQueryService 内部转换；外部消费方通过具体类型访问字段。
 */
data class SymbolPayloadDto(
    val id: String,
    val qualifiedName: String,
    val simpleName: String,
    val filePath: String?,
    val origin: String,
)

data class RelationPayloadDto(
    val id: String,
    val kind: String,
    val fromSymbolId: String,
    val toSymbolId: String,
    val metadata: Map<String, String>,
)
