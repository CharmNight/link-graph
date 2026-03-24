package com.charmnight.linkgraph.model

private val NON_ALNUM = Regex("[^a-z0-9]+")

internal fun normalizeStableComponent(value: String): String {
    val normalized = value.trim().lowercase().replace(NON_ALNUM, "-").trim('-')
    return normalized.ifEmpty { "unknown" }
}

internal fun normalizeStableType(typeName: String): String = normalizeStableComponent(typeName)

data class GraphNode(
    val id: String,
    val type: NodeType,
    val label: String,
    val certainty: Certainty = Certainty.CERTAIN,
    val bindingStatus: BindingStatus = BindingStatus.BOUND,
    val diff: GraphDiff = GraphDiff(),
    val evidence: List<GraphEvidence> = emptyList(),
    val uncertainty: GraphUncertainty? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun stableId(type: NodeType, rawKey: String): String =
            "${normalizeStableType(type.name)}:${normalizeStableComponent(rawKey)}"
    }
}
