package com.charmnight.linkgraph.llm.tools

data class ToolGraphProjectionNodeMapping(
    val projectedNodeId: String,
    val canonicalNodeIds: List<String> = emptyList(),
)

data class ToolGraphProjectionIndex(
    val nodeMappings: Map<String, ToolGraphProjectionNodeMapping> = emptyMap(),
) {
    fun nodeMapping(nodeId: String): ToolGraphProjectionNodeMapping? = nodeMappings[nodeId]

    companion object {
        val EMPTY = ToolGraphProjectionIndex()
    }
}
