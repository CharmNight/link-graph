package com.charmnight.linkgraph.model

data class GraphDiff(
    val status: DiffStatus = DiffStatus.MATCHED,
    val fields: List<String> = emptyList(),
    val counterpartId: String? = null,
    val message: String? = null,
    val summary: String? = null,
    val entries: List<GraphDiffEntry> = emptyList(),
)

enum class GraphDiffElementKind {
    NODE,
    EDGE,
}

data class GraphDiffEntry(
    val elementKind: GraphDiffElementKind,
    val elementId: String,
    val status: DiffStatus,
    val counterpartId: String? = null,
    val fields: List<String> = emptyList(),
    val message: String? = null,
)
