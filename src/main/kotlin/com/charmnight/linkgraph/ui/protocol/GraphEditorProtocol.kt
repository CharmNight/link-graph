package com.charmnight.linkgraph.ui.protocol

object GraphEditorProtocol {
    const val SCHEMA_VERSION: Int = 1
    const val ARTIFACT_SLICE: String = "ARTIFACT_SLICE"
    const val FEEDBACK_SLICE: String = "FEEDBACK_SLICE"

    val INCREMENTAL_TRANSPORT_TYPES: List<String> = listOf(
        ARTIFACT_SLICE,
        FEEDBACK_SLICE,
    )
}
