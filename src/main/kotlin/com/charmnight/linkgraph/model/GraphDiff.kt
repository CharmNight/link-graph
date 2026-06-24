package com.charmnight.linkgraph.model

/**
 * 表示单个图元素的差异摘要。
 *
 * 描述某次比对中该元素的状态（一致/仅一边存在/已修改）以及具体变化字段，
 * 用于差异视图与代码生成等场景的输入。
 */
data class GraphDiff(
    /** 记录当前元素的整体差异状态。 */
    val status: DiffStatus = DiffStatus.MATCHED,
    /** 记录发生变化的字段名称列表。 */
    val fields: List<String> = emptyList(),
    /** 记录另一侧对应元素的标识。 */
    val counterpartId: String? = null,
    /** 记录差异详情说明。 */
    val message: String? = null,
    /** 记录用于界面展示的简要摘要。 */
    val summary: String? = null,
    /** 记录更细粒度的差异条目。 */
    val entries: List<GraphDiffEntry> = emptyList(),
)

/**
 * 标识差异条目作用于节点还是边。
 */
enum class GraphDiffElementKind {
    /** 表示差异条目对应节点。 */
    NODE,
    /** 表示差异条目对应边。 */
    EDGE,
}

/**
 * 表示差异列表中的单条明细记录。
 *
 * 与 [GraphDiff] 类似，但作用在更细的元素粒度（单个节点或单条边），
 * 用于差异视图的列表展示。
 */
data class GraphDiffEntry(
    /** 记录差异元素的种类。 */
    val elementKind: GraphDiffElementKind,
    /** 记录差异元素的唯一标识。 */
    val elementId: String,
    /** 记录该元素的差异状态。 */
    val status: DiffStatus,
    /** 记录对侧元素的标识。 */
    val counterpartId: String? = null,
    /** 记录具体发生变化的字段。 */
    val fields: List<String> = emptyList(),
    /** 记录当前差异条目的解释信息。 */
    val message: String? = null,
)
