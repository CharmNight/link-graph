package com.charmnight.linkgraph.ui

sealed interface GraphEditorTransportEnvelope {
    val sessionId: String
    val revision: Long
    val state: Map<String, Any?>

    data class BootstrapInit(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope

    data class SemanticGraphSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope

    data class LayoutSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope

    data class WorkflowSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope

    data class FeedbackSlice(
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
