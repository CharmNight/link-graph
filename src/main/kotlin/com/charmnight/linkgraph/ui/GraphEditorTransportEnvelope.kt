package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.ui.protocol.GraphEditorProtocol

sealed interface GraphEditorTransportEnvelope {
    val sessionId: String
    val revision: Long
    val state: Map<String, Any?>
    val transportType: String?

    data class Snapshot(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String? = null
    }

    data class ArtifactSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.ARTIFACT_SLICE
    }

    data class FeedbackSlice(
        override val sessionId: String,
        override val revision: Long,
        override val state: Map<String, Any?>,
    ) : GraphEditorTransportEnvelope {
        override val transportType: String = GraphEditorProtocol.FEEDBACK_SLICE
    }
}
