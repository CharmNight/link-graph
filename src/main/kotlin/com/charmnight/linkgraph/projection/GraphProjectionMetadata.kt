package com.charmnight.linkgraph.projection

/**
 * 图投影相关元数据 key 常量集合。
 *
 * 这些 key 写入节点/边的 metadata 中，用于在 UI 渲染时携带额外信息（例如"这是个溢出摘要节点"、
 * "本视图隐藏了多少节点"等）。集中定义避免拼写错误。
 */
object GraphProjectionMetadata {
    /**
     * 溢出（overflow）摘要节点相关 key。
     * 溢出节点用于代替"被裁掉的若干节点"，让 UI 可以提示用户"还有 N 个被隐藏"。
     */
    object Overflow {
        /** 所有溢出相关 key 的统一前缀。 */
        const val PREFIX = "linkGraph.overflow."
        /** 溢出方向（上游/下游）。 */
        const val DIRECTION = "linkGraph.overflow.direction"
        /** 边界节点数（直接与可见区相邻的隐藏节点数）。 */
        const val BOUNDARY_NODE_COUNT = "linkGraph.overflow.boundaryNodeCount"
        /** 可展开节点数（用户点开后会增加多少节点）。 */
        const val EXPANDABLE_NODE_COUNT = "linkGraph.overflow.expandableNodeCount"
        /** 展示形态（例如 "stackedChip"）。 */
        const val PRESENTATION = "linkGraph.overflow.presentation"
        /** 溢出种类。 */
        const val KIND = "linkGraph.overflow.kind"
        /** 关联锚点节点 ID。 */
        const val ANCHOR_NODE_ID = "linkGraph.overflow.anchorNodeId"
        /** 隐藏的方法节点数。 */
        const val HIDDEN_METHOD_COUNT = "linkGraph.overflow.hiddenMethodCount"
        /** 标题前缀（用于生成"还有 N 项"这类标题）。 */
        const val TITLE_PREFIX = "linkGraph.overflow.titlePrefix"
    }

    /** 隐藏统计相关 key。 */
    object Hidden {
        /** 隐藏的节点数。 */
        const val NODE_COUNT = "linkGraph.hiddenNodeCount"
        /** 隐藏的边数。 */
        const val EDGE_COUNT = "linkGraph.hiddenEdgeCount"
        /** 当前方法内隐藏的节点数。 */
        const val CURRENT_METHOD_NODE_COUNT = "linkGraph.hidden.currentMethodNodeCount"
        /** 跨方法隐藏的节点数。 */
        const val CROSS_METHOD_NODE_COUNT = "linkGraph.hidden.crossMethodNodeCount"
    }

    /** 索引投影相关 key（架构图、类图等索引生成的图）。 */
    object Indexed {
        /** 折叠后聚合的节点数。 */
        const val COLLAPSED_COUNT = "indexed.collapsedCount"

        /** 折叠种类细分。 */
        object Collapsed {
            /** 项目源码折叠。 */
            const val PROJECT_SOURCE = "indexed.collapsed.projectSource"
            /** 外部库折叠。 */
            const val EXTERNAL_LIBRARY = "indexed.collapsed.externalLibrary"
            /** JDK 折叠。 */
            const val JDK = "indexed.collapsed.jdk"
            /** 资源折叠。 */
            const val RESOURCE = "indexed.collapsed.resource"
            /** 聚合折叠（综合上述几种）。 */
            const val AGGREGATE = "indexed.collapsed.aggregate"
        }
    }

    /** 与源边（多对一合并的边）相关的 key。 */
    object SourceEdges {
        /** UML 聚合关系对应的源边 ID 列表。 */
        const val UML_AGGREGATE_EDGE_IDS = "uml.relation.aggregate.edgeIds"
    }
}
