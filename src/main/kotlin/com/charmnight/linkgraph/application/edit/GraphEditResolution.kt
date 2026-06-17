package com.charmnight.linkgraph.application.edit

data class GraphEditResolution(
    private val nodeTargetIds: Map<String, String> = emptyMap(),
    private val nodeRemovalIds: Map<String, Set<String>> = emptyMap(),
    private val edgeTargetIds: Map<String, String> = emptyMap(),
    private val edgeRemovalIds: Map<String, Set<String>> = emptyMap(),
) {
    fun nodeTargetId(projectedNodeId: String): String = nodeTargetIds[projectedNodeId] ?: projectedNodeId

    fun nodeRemovalIds(projectedNodeId: String): Set<String> = nodeRemovalIds[projectedNodeId] ?: setOf(projectedNodeId)

    fun edgeTargetId(projectedEdgeId: String): String = edgeTargetIds[projectedEdgeId] ?: projectedEdgeId

    fun edgeRemovalIds(projectedEdgeId: String): Set<String> = edgeRemovalIds[projectedEdgeId] ?: setOf(projectedEdgeId)

    class Builder {
        private val nodeTargetIds = linkedMapOf<String, String>()
        private val nodeRemovalIds = linkedMapOf<String, Set<String>>()
        private val edgeTargetIds = linkedMapOf<String, String>()
        private val edgeRemovalIds = linkedMapOf<String, Set<String>>()

        fun nodeTargetId(projectedNodeId: String, canonicalNodeId: String) {
            nodeTargetIds[projectedNodeId] = canonicalNodeId
        }

        fun nodeRemovalIds(projectedNodeId: String, canonicalNodeIds: Set<String>) {
            nodeRemovalIds[projectedNodeId] = canonicalNodeIds
        }

        fun edgeTargetId(projectedEdgeId: String, canonicalEdgeId: String) {
            edgeTargetIds[projectedEdgeId] = canonicalEdgeId
        }

        fun edgeRemovalIds(projectedEdgeId: String, canonicalEdgeIds: Set<String>) {
            edgeRemovalIds[projectedEdgeId] = canonicalEdgeIds
        }

        fun build(): GraphEditResolution =
            GraphEditResolution(
                nodeTargetIds = nodeTargetIds.toMap(),
                nodeRemovalIds = nodeRemovalIds.toMap(),
                edgeTargetIds = edgeTargetIds.toMap(),
                edgeRemovalIds = edgeRemovalIds.toMap(),
            )
    }

    companion object {
        fun identity(): GraphEditResolution = GraphEditResolution()
    }
}

data class GraphEditPermissionDecision(
    val issues: List<com.charmnight.linkgraph.application.model.GraphEditIssue>,
    val resolution: GraphEditResolution,
)
