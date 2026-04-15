package com.charmnight.linkgraph.ui

sealed interface GraphEditorTransportEnvelope {
    val sessionId: String
    val revision: Long
    val state: Map<String, Any?>

    data class Snapshot(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope

    data class ArtifactSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope
}
