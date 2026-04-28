package com.charmnight.linkgraph.ui

/**
 * 渲染前后端之间的权威快照 transport。
 * 除按需回填 artifact 外，每个 snapshotRevision 只发送一份完整快照。
 */
class GraphEditorTransportSliceRenderer(
    private val pageRenderer: GraphEditorPageRenderer = GraphEditorPageRenderer(),
    private val artifactRegistry: GraphEditorArtifactRegistry = GraphEditorArtifactRegistry(),
) {
    private var currentArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts =
        GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY

    fun renderBootstrapInitScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String {
        val artifactRefs = artifactRegistry.replaceWith(snapshot)
        currentArtifactRefs = artifactRefs
        return renderScript(
            listOf(
                GraphEditorTransportEnvelope.Snapshot(
                    sessionId = sessionId,
                    revision = snapshot.snapshotRevision,
                    state = pageRenderer.bootstrapPayload(snapshot, artifactRefs),
                ),
            ),
        )
    }

    fun renderIncrementalEnvelopes(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): List<GraphEditorTransportEnvelope> {
        val previousPayload = pageRenderer.bootstrapPayload(
            previousSnapshot,
            artifactRegistry.replaceWith(previousSnapshot),
        )
        val currentArtifactRefs = artifactRegistry.replaceWith(snapshot)
        this.currentArtifactRefs = currentArtifactRefs
        val currentPayload = pageRenderer.bootstrapPayload(snapshot, currentArtifactRefs)
        if (previousPayload == currentPayload) {
            return emptyList()
        }
        return listOf(
            GraphEditorTransportEnvelope.Snapshot(
                sessionId = sessionId,
                revision = snapshot.snapshotRevision,
                state = currentPayload,
            ),
        )
    }

    fun renderIncrementalScript(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String? {
        val envelopes = renderIncrementalEnvelopes(sessionId, previousSnapshot, snapshot)
        if (envelopes.isEmpty()) {
            return null
        }
        return renderScript(envelopes)
    }

    fun renderScript(envelopes: List<GraphEditorTransportEnvelope>): String {
        return envelopes.joinToString(separator = "\n") { envelope ->
            val payload = when (envelope) {
                is GraphEditorTransportEnvelope.Snapshot -> linkedMapOf(
                    "sessionId" to envelope.sessionId,
                    "revision" to envelope.revision,
                    "state" to envelope.state,
                )
                is GraphEditorTransportEnvelope.ArtifactSlice -> linkedMapOf(
                    "type" to "ARTIFACT_SLICE",
                    "sessionId" to envelope.sessionId,
                    "revision" to envelope.revision,
                    "state" to envelope.state,
                )
            }
            val envelopeJson = pageRenderer.sanitizeJson(pageRenderer.toJson(payload))
            """window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));"""
        }
    }

    fun artifactContents(artifactIds: Collection<String>): Map<String, String> {
        return artifactRegistry.readAll(artifactIds)
    }

    fun currentArtifactRefs(): GraphEditorArtifactRegistry.SnapshotArtifacts = currentArtifactRefs
}
