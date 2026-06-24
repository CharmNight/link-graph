package com.charmnight.linkgraph.presentation

/**
 * 图视图的呈现规范。
 *
 * 把"如何展示这份图"的所有可视化指令打包，让 UI 层可以按统一接口渲染。
 * 与图数据本身（节点/边）解耦——同一份图可以有多种呈现规范。
 */
data class GraphViewPresentation(
    /** 当前视图的目标（锚点 + 标题等）。 */
    val target: GraphPresentationTarget = GraphPresentationTarget(),
    /** 视图泳道列表。 */
    val lanes: List<GraphPresentationLane> = emptyList(),
    /** 隐藏桶列表（被折叠的节点/边按桶分组）。 */
    val hiddenBuckets: List<GraphHiddenBucket> = emptyList(),
    /** 视图可用控件状态。 */
    val controls: GraphPresentationControls = GraphPresentationControls(),
)

/** 视图目标信息：UI 在视图顶部展示的"当前焦点"。 */
data class GraphPresentationTarget(
    /** 焦点节点 ID；null 表示无特定焦点。 */
    val nodeId: String? = null,
    /** 焦点标题。 */
    val title: String = "",
    /** 焦点副标题。 */
    val subtitle: String = "",
    /** 源码位置字符串；用于"跳转到源码"按钮。 */
    val location: String? = null,
)

/**
 * 视图泳道。
 *
 * 泳道把图节点按某种维度（例如层次、模块）分组到不同列/行/区域，
 * 让大规模图更易阅读。
 */
data class GraphPresentationLane(
    /** 泳道 ID。 */
    val id: String,
    /** 泳道标签。 */
    val label: String,
    /** 泳道轴向（列/行/区域）。 */
    val axis: GraphPresentationLaneAxis,
    /** 排序值。 */
    val order: Int,
    /** 角色标识；例如 "anchor"、"caller"、"callee" 等。 */
    val role: String,
)

/** 泳道轴向枚举。 */
enum class GraphPresentationLaneAxis {
    /** 列（垂直泳道）。 */
    COLUMN,

    /** 行（水平泳道）。 */
    ROW,

    /** 区域（不强制方向，按区域分组）。 */
    ZONE,
}

/**
 * 隐藏桶：被折叠的节点/边的分组。
 *
 * 投影过程裁掉的节点不会直接消失，而是按桶分组聚合为单个虚拟节点，
 * 让 UI 可以提示"此处隐藏了 N 个 X 类节点"。
 */
data class GraphHiddenBucket(
    /** 桶 ID。 */
    val id: String,
    /** 桶标签。 */
    val label: String,
    /** 桶内节点/边数量。 */
    val count: Int,
    /** 桶内节点 ID 列表。 */
    val nodeIds: List<String> = emptyList(),
    /** 桶内边 ID 列表。 */
    val edgeIds: List<String> = emptyList(),
)

/** 视图可用控件状态：让 UI 知道是否启用搜索、展开等控件。 */
data class GraphPresentationControls(
    /** 主作用域名称；例如 "项目架构"、"当前方法"。 */
    val primaryScope: String = "",
    /** 可切换的作用域列表。 */
    val availableScopes: List<String> = emptyList(),
    /** 是否启用搜索。 */
    val searchable: Boolean = true,
    /** 是否允许展开（overflow 节点等）。 */
    val expandable: Boolean = true,
)
