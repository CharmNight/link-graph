package com.charmnight.linkgraph.model

data class GraphEdge(
    val id: String,
    val type: EdgeType,
    val fromNodeId: String,
    val toNodeId: String,
    val certainty: Certainty = Certainty.CERTAIN,
    val bindingStatus: BindingStatus = BindingStatus.BOUND,
    val diff: GraphDiff = GraphDiff(),
    val evidence: List<GraphEvidence> = emptyList(),
    val uncertainty: GraphUncertainty? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun stableId(type: EdgeType, fromNodeId: String, toNodeId: String): String {
            val typePart = normalizeStableType(type.name)
            val fromPart = normalizeStableComponent(fromNodeId)
            val toPart = normalizeStableComponent(toNodeId)
            return "$typePart:$fromPart->$toPart"
        }
    }
}
