package com.charmnight.linkgraph.model

data class GraphEdge(
    val id: String,
    val type: EdgeType,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String? = null,
    val certainty: Certainty = Certainty.PROVEN,
    val status: String? = null,
    val diff: GraphDiff = GraphDiff(),
    val evidence: List<GraphEvidence> = emptyList(),
    val uncertainty: GraphUncertainty? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun stableId(
            type: EdgeType,
            fromNodeId: String,
            toNodeId: String,
            ownerContext: String? = null,
        ): String {
            val typePart = normalizeStableType(type.name)
            val ownerPart = ownerContext?.let { normalizeStableComponent(it) }?.takeIf { it.isNotBlank() }
            val fromPart = normalizeStableComponent(fromNodeId)
            val toPart = normalizeStableComponent(toNodeId)
            return if (ownerPart == null) {
                "$typePart:$fromPart->$toPart"
            } else {
                "$typePart:$ownerPart/$fromPart->$toPart"
            }
        }
    }
}
