package com.charmnight.linkgraph.presentation

data class GraphViewPresentation(
    val target: GraphPresentationTarget = GraphPresentationTarget(),
    val lanes: List<GraphPresentationLane> = emptyList(),
    val hiddenBuckets: List<GraphHiddenBucket> = emptyList(),
    val controls: GraphPresentationControls = GraphPresentationControls(),
)

data class GraphPresentationTarget(
    val nodeId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val location: String? = null,
)

data class GraphPresentationLane(
    val id: String,
    val label: String,
    val axis: GraphPresentationLaneAxis,
    val order: Int,
    val role: String,
)

enum class GraphPresentationLaneAxis {
    COLUMN,
    ROW,
    ZONE,
}

data class GraphHiddenBucket(
    val id: String,
    val label: String,
    val count: Int,
    val nodeIds: List<String> = emptyList(),
    val edgeIds: List<String> = emptyList(),
)

data class GraphPresentationControls(
    val primaryScope: String = "",
    val availableScopes: List<String> = emptyList(),
    val searchable: Boolean = true,
    val expandable: Boolean = true,
)
