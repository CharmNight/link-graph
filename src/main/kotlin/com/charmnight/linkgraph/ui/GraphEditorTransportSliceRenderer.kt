package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.json.JsonCodec

/**
 * 渲染前后端之间的权威快照 transport。
 * 除按需回填 artifact 外，每个 snapshotRevision 只发送一份完整快照。
 */
class GraphEditorTransportSliceRenderer(
    private val pageRenderer: GraphEditorPageRenderer = GraphEditorPageRenderer(),
    private val artifactRegistry: GraphEditorArtifactRegistry = GraphEditorArtifactRegistry(),
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private var currentArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts =
        GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY

    fun renderBootstrapInitScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String {
        val artifactStartedAt = System.nanoTime()
        val artifactRefs = artifactRegistry.replaceWith(snapshot)
        currentArtifactRefs = artifactRefs
        traceStage(
            stage = "transport.bootstrap.artifacts",
            startedAtNanos = artifactStartedAt,
        ) {
            snapshotDetails(snapshot)
        }
        val payloadStartedAt = System.nanoTime()
        val state = pageRenderer.bootstrapPayload(snapshot, artifactRefs)
        traceStage(
            stage = "transport.bootstrap.payload",
            startedAtNanos = payloadStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(state)
        }
        return renderScript(
            listOf(
                GraphEditorTransportEnvelope.Snapshot(
                    sessionId = sessionId,
                    revision = snapshot.snapshotRevision,
                    state = state,
                ),
            ),
        )
    }

    fun renderIncrementalEnvelopes(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): List<GraphEditorTransportEnvelope> {
        val previousStartedAt = System.nanoTime()
        val previousPayload = pageRenderer.bootstrapPayload(
            previousSnapshot,
            artifactRegistry.replaceWith(previousSnapshot),
        )
        traceStage(
            stage = "transport.payload.previous",
            startedAtNanos = previousStartedAt,
        ) {
            snapshotDetails(previousSnapshot) + payloadDetails(previousPayload)
        }
        val currentStartedAt = System.nanoTime()
        val currentArtifactRefs = artifactRegistry.replaceWith(snapshot)
        this.currentArtifactRefs = currentArtifactRefs
        val currentPayload = pageRenderer.bootstrapPayload(snapshot, currentArtifactRefs)
        traceStage(
            stage = "transport.payload.current",
            startedAtNanos = currentStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(currentPayload)
        }
        val compareStartedAt = System.nanoTime()
        val unchanged = previousPayload == currentPayload
        traceStage(
            stage = "transport.payload.compare",
            startedAtNanos = compareStartedAt,
        ) {
            listOf(
                "unchanged=$unchanged",
                "previousRevision=${previousSnapshot.snapshotRevision}",
                "currentRevision=${snapshot.snapshotRevision}",
            )
        }
        if (unchanged) {
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
        val startedAt = System.nanoTime()
        val script = envelopes.joinToString(separator = "\n") { envelope ->
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
            val envelopeJson = JsonCodec.toScriptSafeJson(payload)
            """window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));"""
        }
        traceStage(
            stage = "transport.renderScript",
            startedAtNanos = startedAt,
        ) {
            listOf(
                "envelopes=${envelopes.size}",
                "scriptChars=${script.length}",
                "revisions=${envelopes.joinToString(separator = "|") { it.revision.toString() }}",
            )
        }
        return script
    }

    fun artifactContents(artifactIds: Collection<String>): Map<String, String> {
        return artifactRegistry.readAll(artifactIds)
    }

    fun currentArtifactRefs(): GraphEditorArtifactRegistry.SnapshotArtifacts = currentArtifactRefs

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }

    private fun snapshotDetails(snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot): List<String> = listOf(
        "snapshotRevision=${snapshot.snapshotRevision}",
        "lastMessageType=${snapshot.lastMessageType}",
        "workspace=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceGraph)}",
        "workspaceBase=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceBaseGraph)}",
        "semanticFact=${LinkGraphRenderTrace.graphSummary(snapshot.semanticFactGraph)}",
        "factVisible=${LinkGraphRenderTrace.graphSummary(snapshot.factGraphView.visibleGraph)}",
        "flowVisible=${LinkGraphRenderTrace.graphSummary(snapshot.flowchartView.visibleGraph)}",
        "resourceVisible=${LinkGraphRenderTrace.graphSummary(snapshot.resourceRelationView.visibleGraph)}",
        "architectureVisible=${LinkGraphRenderTrace.graphSummary(snapshot.architectureGraphView.visibleGraph)}",
        "classDiagramVisible=${LinkGraphRenderTrace.graphSummary(snapshot.classDiagramView.visibleGraph)}",
    )

    private fun payloadDetails(payload: Map<String, Any?>): List<String> = listOf(
        "payloadKeys=${payload.size}",
        "hasWorkspaceGraph=${payload.containsKey("workspaceGraph")}",
        "hasFlowchartView=${payload.containsKey("flowchartView")}",
        "hasArchitectureGraphView=${payload.containsKey("architectureGraphView")}",
        "hasClassDiagramView=${payload.containsKey("classDiagramView")}",
    )
}
