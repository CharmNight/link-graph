package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge

/**
 * GraphEditorPageRenderer 的纯展示 / 序列化 helper（P2-1 拆分）。
 *
 * 这些函数无状态、把领域对象转换为前端可消费的字符串 / Map，
 * 与 GraphEditorPageRenderer 的 HTML 渲染 / bootstrap payload 装配主流程解耦后便于复用与单独测试。
 */

/**
 * 解析差异条目的可读标题。
 *
 * NODE 类条目优先用文档中对应节点的 title；EDGE 类条目直接用 elementId（边没有 title 字段）。
 */
internal fun resolveDiffTitle(
    entry: GraphDiffEntry,
    document: GraphDocument,
): String = when (entry.elementKind) {
    GraphDiffElementKind.NODE -> document.nodes.firstOrNull { it.id == entry.elementId }?.title ?: entry.elementId
    GraphDiffElementKind.EDGE -> entry.elementId
}

/** 把边转换为前端使用的 Map 结构（id / type / source / target / label / metadata / sourceTag）。 */
internal fun edgeToMap(edge: GraphEdge): Map<String, Any?> = linkedMapOf(
    "id" to edge.id,
    "type" to edge.type.name,
    "source" to edge.fromNodeId,
    "target" to edge.toNodeId,
    "label" to edge.label,
    "metadata" to edge.metadata,
    "sourceTag" to edge.sourceTag.name,
)
