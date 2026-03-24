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
    val title: String,
    val location: String? = null,
    val signature: String? = null,
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val doc: String? = null,
    val sourceKind: String? = null,
    val status: String? = null,
    val bindingStatus: BindingStatus = BindingStatus.BOUND,
    val certainty: Certainty = Certainty.PROVEN,
    val diff: GraphDiff = GraphDiff(),
    val evidence: List<GraphEvidence> = emptyList(),
    val uncertainty: GraphUncertainty? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun stableId(type: NodeType, rawKey: String, ownerContext: String? = null): String {
            val typePart = normalizeStableType(type.name)
            val ownerPart = ownerContext?.let { normalizeStableComponent(it) }?.takeIf { it.isNotBlank() }
            val keyPart = normalizeStableComponent(rawKey)
            return if (ownerPart == null) {
                "$typePart:$keyPart"
            } else {
                "$typePart:$ownerPart/$keyPart"
            }
        }
    }
}
