package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.json.JsonCodec
import java.security.MessageDigest

/**
 * 渲染前后端之间的权威快照 transport。
 * 除按需回填 artifact 外，每个 snapshotRevision 只发送一份完整快照。
 */
class GraphEditorTransportSliceRenderer(
    private val pageRenderer: GraphEditorPageRenderer = GraphEditorPageRenderer(),
    private val artifactRegistry: GraphEditorArtifactRegistry = GraphEditorArtifactRegistry(),
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private val artifactSlicePayloadBuilder = GraphEditorArtifactSlicePayloadBuilder(pageRenderer)

    class RenderedSnapshotScript internal constructor(
        val revision: Long,
        val script: String,
        val envelopes: List<GraphEditorTransportEnvelope>,
        internal val artifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts,
        internal val artifactContents: GraphEditorArtifactRegistry.PreparedSnapshotArtifacts?,
        internal val payloadHash: String?,
    )

    @Volatile
    private var currentArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts =
        GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY
    @Volatile
    private var lastRenderedPayloadHash: String? = null

    fun prepareSnapshotArtifacts(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorArtifactRegistry.SnapshotArtifacts {
        val artifactStartedAt = System.nanoTime()
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        traceStage(
            stage = "transport.prepareSnapshotArtifacts",
            startedAtNanos = artifactStartedAt,
        ) {
            snapshotDetails(snapshot)
        }
        commitPreparedSnapshotArtifacts(preparedArtifacts)
        return preparedArtifacts.refs
    }

    fun prepareBootstrapSnapshotArtifacts(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorArtifactRegistry.SnapshotArtifacts {
        val artifactStartedAt = System.nanoTime()
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        traceStage(
            stage = "transport.prepareBootstrapSnapshotArtifacts",
            startedAtNanos = artifactStartedAt,
        ) {
            snapshotDetails(snapshot)
        }
        val payloadStartedAt = System.nanoTime()
        val state = pageRenderer.bootstrapPayload(snapshot, preparedArtifacts.refs)
        traceStage(
            stage = "transport.bootstrap.payload",
            startedAtNanos = payloadStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(state)
        }
        commitPreparedSnapshotArtifacts(preparedArtifacts)
        lastRenderedPayloadHash = payloadHash(state)
        return preparedArtifacts.refs
    }

    fun renderBootstrapInitScript(
        sessionId: String,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String {
        val artifactRefs = prepareBootstrapSnapshotArtifacts(snapshot)
        val payloadStartedAt = System.nanoTime()
        val state = pageRenderer.bootstrapPayload(snapshot, artifactRefs)
        lastRenderedPayloadHash = payloadHash(state)
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
    ): List<GraphEditorTransportEnvelope> =
        renderIncrementalSnapshotScript(
            sessionId = sessionId,
            previousSnapshot = previousSnapshot,
            snapshot = snapshot,
        )?.envelopes.orEmpty()

    fun renderIncrementalSnapshotScript(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): RenderedSnapshotScript? {
        if (snapshot.snapshotRevision == previousSnapshot.snapshotRevision) {
            traceStage(
                stage = "transport.payload.compare",
                startedAtNanos = System.nanoTime(),
            ) {
                listOf(
                    "unchanged=true",
                    "reason=sameRevision",
                    "revision=${snapshot.snapshotRevision}",
                )
            }
            return null
        }
        buildFeedbackSliceEnvelope(sessionId, previousSnapshot, snapshot)?.let { envelope ->
            return RenderedSnapshotScript(
                revision = snapshot.snapshotRevision,
                script = renderScript(listOf(envelope)),
                envelopes = listOf(envelope),
                artifactRefs = currentArtifactRefs,
                artifactContents = null,
                payloadHash = null,
            )
        }
        buildArtifactSliceEnvelope(sessionId, previousSnapshot, snapshot)?.let { (envelope, preparedArtifacts) ->
            return RenderedSnapshotScript(
                revision = snapshot.snapshotRevision,
                script = renderScript(listOf(envelope)),
                envelopes = listOf(envelope),
                artifactRefs = preparedArtifacts.refs,
                artifactContents = preparedArtifacts,
                payloadHash = null,
            )
        }
        val currentStartedAt = System.nanoTime()
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        val currentPayload = pageRenderer.bootstrapPayload(snapshot, preparedArtifacts.refs)
        val currentPayloadHash = payloadHash(currentPayload)
        traceStage(
            stage = "transport.payload.current",
            startedAtNanos = currentStartedAt,
        ) {
            snapshotDetails(snapshot) + payloadDetails(currentPayload)
        }
        val compareStartedAt = System.nanoTime()
        val unchanged = currentPayloadHash == lastRenderedPayloadHash
        traceStage(
            stage = "transport.payload.compare",
            startedAtNanos = compareStartedAt,
        ) {
            listOf(
                "unchanged=$unchanged",
                "previousRevision=${previousSnapshot.snapshotRevision}",
                "currentRevision=${snapshot.snapshotRevision}",
                "strategy=currentPayloadHash",
            )
        }
        if (unchanged) {
            return null
        }
        val envelopes = listOf(
            GraphEditorTransportEnvelope.Snapshot(
                sessionId = sessionId,
                revision = snapshot.snapshotRevision,
                state = currentPayload,
            ),
        )
        return RenderedSnapshotScript(
            revision = snapshot.snapshotRevision,
            script = renderScript(envelopes),
            envelopes = envelopes,
            artifactRefs = preparedArtifacts.refs,
            artifactContents = preparedArtifacts,
            payloadHash = currentPayloadHash,
        )
    }

    fun renderIncrementalScript(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): String? {
        return renderIncrementalSnapshotScript(
            sessionId = sessionId,
            previousSnapshot = previousSnapshot,
            snapshot = snapshot,
        )?.script
    }

    fun commitRenderedSnapshot(renderedSnapshotScript: RenderedSnapshotScript) {
        renderedSnapshotScript.artifactContents?.let(::commitPreparedSnapshotArtifacts)
        renderedSnapshotScript.payloadHash?.let { payloadHash ->
            lastRenderedPayloadHash = payloadHash
        }
    }

    private fun commitPreparedSnapshotArtifacts(
        preparedArtifacts: GraphEditorArtifactRegistry.PreparedSnapshotArtifacts,
    ) {
        artifactRegistry.replaceWith(preparedArtifacts)
        currentArtifactRefs = preparedArtifacts.refs
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
                    "type" to envelope.transportType,
                    "sessionId" to envelope.sessionId,
                    "revision" to envelope.revision,
                    "state" to envelope.state,
                )
                is GraphEditorTransportEnvelope.FeedbackSlice -> linkedMapOf(
                    "type" to envelope.transportType,
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

    private fun buildFeedbackSliceEnvelope(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): GraphEditorTransportEnvelope.FeedbackSlice? {
        val previousWithoutFeedback = previousSnapshot.copy(
            snapshotRevision = snapshot.snapshotRevision,
            operationFeedback = snapshot.operationFeedback,
            lastMessageType = snapshot.lastMessageType,
        )
        if (previousWithoutFeedback != snapshot) {
            return null
        }
        val state = linkedMapOf<String, Any?>(
            "snapshotRevision" to snapshot.snapshotRevision,
            "operationFeedback" to snapshot.operationFeedback?.let { feedback ->
                linkedMapOf(
                    "level" to feedback.level.name,
                    "message" to feedback.message,
                )
            },
            "lastMessageType" to snapshot.lastMessageType,
        )
        return GraphEditorTransportEnvelope.FeedbackSlice(
            sessionId = sessionId,
            revision = snapshot.snapshotRevision,
            state = state,
        )
    }

    private fun buildArtifactSliceEnvelope(
        sessionId: String,
        previousSnapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): Pair<GraphEditorTransportEnvelope.ArtifactSlice, GraphEditorArtifactRegistry.PreparedSnapshotArtifacts>? {
        val preparedArtifacts = artifactRegistry.prepare(snapshot)
        if (preparedArtifacts.refs == currentArtifactRefs) {
            return null
        }
        if (stripArtifactPayloads(previousSnapshot) != stripArtifactPayloads(snapshot)) {
            return null
        }
        val artifactContents = preparedArtifacts.contents
            .filterKeys { artifactId -> currentArtifactRefs.containsArtifactId(artifactId).not() }
        if (artifactContents.isEmpty()) {
            return null
        }
        val state = artifactSlicePayloadBuilder.build(
            snapshot = snapshot,
            previousArtifactRefs = currentArtifactRefs,
            artifactRefs = preparedArtifacts.refs,
            artifactContents = artifactContents,
        )
        return GraphEditorTransportEnvelope.ArtifactSlice(
            sessionId = sessionId,
            revision = snapshot.snapshotRevision,
            state = state,
        ) to preparedArtifacts
    }

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

    private fun payloadHash(payload: Map<String, Any?>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(JsonCodec.toJson(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun GraphEditorArtifactRegistry.SnapshotArtifacts.containsArtifactId(artifactId: String): Boolean {
        if (artifactId == qaPromptPreviewArtifactId ||
            artifactId == diffReviewPromptPreviewArtifactId ||
            artifactId == beautificationPromptPreviewArtifactId ||
            artifactId == generationPlanPromptPreviewArtifactId ||
            artifactId == generationPlanDiscussionPromptPreviewArtifactId ||
            artifactId == generatedCodeDraftPromptPreviewArtifactId
        ) {
            return true
        }
        if (artifactId in generatedCodeDraftContentArtifactIds.values) {
            return true
        }
        return assistantResultArtifacts.values.any { artifacts ->
            artifactId == artifacts.qaPromptPreviewArtifactId ||
                artifactId == artifacts.explanationPromptPreviewArtifactId ||
                artifactId == artifacts.checkPromptPreviewArtifactId ||
                artifactId == artifacts.generationPlanPromptPreviewArtifactId ||
                artifactId == artifacts.generationDiscussionPromptPreviewArtifactId ||
                artifactId in artifacts.codeDraftContentArtifactIds.values
        }
    }

    private fun stripArtifactPayloads(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        return snapshot.copy(
            snapshotRevision = 0,
            qaResult = snapshot.qaResult?.copy(promptPreview = ""),
            diffReviewResult = snapshot.diffReviewResult?.copy(promptPreview = ""),
            graphBeautificationResult = snapshot.graphBeautificationResult?.copy(promptPreview = ""),
            generationPlan = snapshot.generationPlan?.copy(promptPreview = ""),
            generationPlanDiscussionSession = snapshot.generationPlanDiscussionSession?.copy(promptPreview = null),
            generatedCodeDrafts = snapshot.generatedCodeDrafts.map { draft -> draft.copy(content = null) },
            generatedCodeDraftPromptPreview = null,
            assistantResultStore = stripAssistantArtifactPayloads(snapshot.assistantResultStore),
        )
    }

    private fun stripAssistantArtifactPayloads(
        store: com.charmnight.linkgraph.workbench.AssistantResultStore,
    ): com.charmnight.linkgraph.workbench.AssistantResultStore {
        return store.copy(
            results = store.results.mapValues { (_, entry) ->
                entry.copy(
                    qa = entry.qa?.copy(promptPreview = ""),
                    explanation = entry.explanation?.copy(promptPreview = ""),
                    generationPlan = entry.generationPlan?.copy(promptPreview = ""),
                    generationDiscussionSession = entry.generationDiscussionSession?.copy(promptPreview = null),
                    codeDrafts = entry.codeDrafts.map { draft -> draft.copy(content = null) },
                    check = entry.check?.copy(promptPreview = ""),
                )
            },
        )
    }
}
