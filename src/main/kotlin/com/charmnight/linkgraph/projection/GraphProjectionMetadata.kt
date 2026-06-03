package com.charmnight.linkgraph.projection

object GraphProjectionMetadata {
    object Overflow {
        const val PREFIX = "linkGraph.overflow."
        const val DIRECTION = "linkGraph.overflow.direction"
        const val BOUNDARY_NODE_COUNT = "linkGraph.overflow.boundaryNodeCount"
        const val EXPANDABLE_NODE_COUNT = "linkGraph.overflow.expandableNodeCount"
        const val PRESENTATION = "linkGraph.overflow.presentation"
        const val KIND = "linkGraph.overflow.kind"
        const val ANCHOR_NODE_ID = "linkGraph.overflow.anchorNodeId"
        const val HIDDEN_METHOD_COUNT = "linkGraph.overflow.hiddenMethodCount"
        const val TITLE_PREFIX = "linkGraph.overflow.titlePrefix"
    }

    object Hidden {
        const val NODE_COUNT = "linkGraph.hiddenNodeCount"
        const val EDGE_COUNT = "linkGraph.hiddenEdgeCount"
        const val CURRENT_METHOD_NODE_COUNT = "linkGraph.hidden.currentMethodNodeCount"
        const val CROSS_METHOD_NODE_COUNT = "linkGraph.hidden.crossMethodNodeCount"
    }

    object Indexed {
        const val COLLAPSED_COUNT = "indexed.collapsedCount"

        object Collapsed {
            const val PROJECT_SOURCE = "indexed.collapsed.projectSource"
            const val EXTERNAL_LIBRARY = "indexed.collapsed.externalLibrary"
            const val JDK = "indexed.collapsed.jdk"
            const val RESOURCE = "indexed.collapsed.resource"
            const val AGGREGATE = "indexed.collapsed.aggregate"
        }
    }

    object SourceEdges {
        const val UML_AGGREGATE_EDGE_IDS = "uml.relation.aggregate.edgeIds"
    }
}
