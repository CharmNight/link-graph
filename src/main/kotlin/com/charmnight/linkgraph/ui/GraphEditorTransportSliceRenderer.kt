package com.charmnight.linkgraph.ui

/**
 * 根据前后快照差异生成增量 transport envelope，避免每次都重发整份 bootstrap。
 */
class GraphEditorTransportSliceRenderer(
    private val pageRenderer: GraphEditorPageRenderer = GraphEditorPageRenderer(),
    private val artifactRegistry: GraphEditorArtifactRegistry = GraphEditorArtifactRegistry(),
) {
    private var currentArtifactRefs: GraphEditorArtifactRegistry.SnapshotArtifacts =
        GraphEditorArtifactRegistry.SnapshotArtifacts.EMPTY

    fun renderBootstrapInitScript(
        sessionId: String,
        snapshot: GraphEditorStateService.Snapshot,
    ): String {
        val artifactRefs = artifactRegistry.replaceWith(snapshot)
        currentArtifactRefs = artifactRefs
        val payload = pageRenderer.bootstrapPayload(snapshot, artifactRefs)
        return renderScript(
            listOf(
                GraphEditorTransportEnvelope.BootstrapInit(
                    sessionId = sessionId,
                    revision = snapshot.snapshotRevision,
                    state = payload,
                ),
            ),
        )
    }

    fun renderIncrementalEnvelopes(
        sessionId: String,
        previousSnapshot: GraphEditorStateService.Snapshot,
        snapshot: GraphEditorStateService.Snapshot,
    ): List<GraphEditorTransportEnvelope> {
        val previousArtifactRefs = artifactRegistry.replaceWith(previousSnapshot)
        val previousPayload = pageRenderer.bootstrapPayload(previousSnapshot, previousArtifactRefs)
        val currentArtifactRefs = artifactRegistry.replaceWith(snapshot)
        this.currentArtifactRefs = currentArtifactRefs
        val currentPayload = pageRenderer.bootstrapPayload(snapshot, currentArtifactRefs)
        val revision = snapshot.snapshotRevision
        val envelopes = mutableListOf<GraphEditorTransportEnvelope>()

        if (semanticGraphChanged(previousSnapshot, snapshot)) {
            envelopes += GraphEditorTransportEnvelope.SemanticGraphSlice(
                sessionId = sessionId,
                revision = revision,
                state = currentPayload.selectKeys(SEMANTIC_GRAPH_KEYS),
            )
        } else if (layoutOnlyChanged(previousSnapshot, snapshot)) {
            envelopes += GraphEditorTransportEnvelope.LayoutSlice(
                sessionId = sessionId,
                revision = revision,
                state = currentPayload.selectKeys(LAYOUT_KEYS),
            )
        }

        if (workflowChanged(previousPayload, currentPayload)) {
            envelopes += GraphEditorTransportEnvelope.WorkflowSlice(
                sessionId = sessionId,
                revision = revision,
                state = currentPayload.selectKeys(WORKFLOW_KEYS),
            )
        }

        if (feedbackChanged(previousPayload, currentPayload)) {
            envelopes += GraphEditorTransportEnvelope.FeedbackSlice(
                sessionId = sessionId,
                revision = revision,
                state = currentPayload.selectKeys(FEEDBACK_KEYS),
            )
        }

        return envelopes
    }

    fun renderIncrementalScript(
        sessionId: String,
        previousSnapshot: GraphEditorStateService.Snapshot,
        snapshot: GraphEditorStateService.Snapshot,
    ): String? {
        val envelopes = renderIncrementalEnvelopes(sessionId, previousSnapshot, snapshot)
        if (envelopes.isEmpty()) {
            return null
        }
        return renderScript(envelopes)
    }

    fun renderScript(envelopes: List<GraphEditorTransportEnvelope>): String {
        return envelopes.joinToString(separator = "\n") { envelope ->
            val envelopeJson = pageRenderer.sanitizeJson(
                pageRenderer.toJson(
                    linkedMapOf(
                        "type" to envelopeType(envelope),
                        "sessionId" to envelope.sessionId,
                        "revision" to envelope.revision,
                        "state" to envelope.state,
                    ),
                ),
            )
            """window.dispatchEvent(new CustomEvent("link-graph-bootstrap", { detail: $envelopeJson }));"""
        }
    }

    private fun envelopeType(envelope: GraphEditorTransportEnvelope): String {
        return when (envelope) {
            is GraphEditorTransportEnvelope.BootstrapInit -> "BOOTSTRAP_INIT"
            is GraphEditorTransportEnvelope.SemanticGraphSlice -> "SEMANTIC_GRAPH_SLICE"
            is GraphEditorTransportEnvelope.LayoutSlice -> "LAYOUT_SLICE"
            is GraphEditorTransportEnvelope.WorkflowSlice -> "WORKFLOW_SLICE"
            is GraphEditorTransportEnvelope.FeedbackSlice -> "FEEDBACK_SLICE"
            is GraphEditorTransportEnvelope.ArtifactSlice -> "ARTIFACT_SLICE"
        }
    }

    private fun semanticGraphChanged(
        previousSnapshot: GraphEditorStateService.Snapshot,
        snapshot: GraphEditorStateService.Snapshot,
    ): Boolean {
        return previousSnapshot.semanticRevision != snapshot.semanticRevision ||
            previousSnapshot.analysisDisplayMode != snapshot.analysisDisplayMode
    }

    private fun layoutOnlyChanged(
        previousSnapshot: GraphEditorStateService.Snapshot,
        snapshot: GraphEditorStateService.Snapshot,
    ): Boolean {
        return previousSnapshot.semanticRevision == snapshot.semanticRevision &&
            previousSnapshot.layoutRevision != snapshot.layoutRevision
    }

    private fun workflowChanged(
        previousPayload: Map<String, Any?>,
        currentPayload: Map<String, Any?>,
    ): Boolean {
        return previousPayload.selectKeys(WORKFLOW_COMPARE_KEYS) != currentPayload.selectKeys(WORKFLOW_COMPARE_KEYS)
    }

    private fun feedbackChanged(
        previousPayload: Map<String, Any?>,
        currentPayload: Map<String, Any?>,
    ): Boolean {
        return previousPayload.selectKeys(FEEDBACK_COMPARE_KEYS) != currentPayload.selectKeys(FEEDBACK_COMPARE_KEYS)
    }

    private fun Map<String, Any?>.selectKeys(keys: Set<String>): LinkedHashMap<String, Any?> {
        val selected = linkedMapOf<String, Any?>()
        keys.forEach { key ->
            if (containsKey(key)) {
                selected[key] = get(key)
            }
        }
        return selected
    }

    fun artifactContents(artifactIds: Collection<String>): Map<String, String> {
        return artifactRegistry.readAll(artifactIds)
    }

    fun currentArtifactRefs(): GraphEditorArtifactRegistry.SnapshotArtifacts = currentArtifactRefs

    private companion object {
        private val SEMANTIC_GRAPH_KEYS = setOf(
            "analysisDisplayMode",
            "visibleGraph",
            "workingGraph",
            "referenceFactGraph",
            "designBaselineGraph",
            "factGraphView",
            "flowchartView",
            "resourceRelationView",
            "semanticRevision",
            "layoutRevision",
            "snapshotRevision",
            "selectedNodeId",
            "lastMessageType",
            "lastGraphSource",
        )
        private val LAYOUT_KEYS = setOf(
            "layoutState",
            "layoutRevision",
            "snapshotRevision",
            "lastMessageType",
        )
        private val WORKFLOW_KEYS = setOf(
            "draftPatchPreview",
            "canUndoDraftPatchApply",
            "lastAppliedDraftPatchSummary",
            "lastDraftPatchApplyResult",
            "auditResult",
            "auditRequestState",
            "diffReviewResult",
            "diffReviewRequestState",
            "graphBeautificationResult",
            "graphBeautificationRequestState",
            "mermaidIssues",
            "diffItems",
            "syncPreviewItems",
            "generationPlan",
            "generationPlanRequestState",
            "generatedCodeDrafts",
            "generatedCodeDraftWarnings",
            "generatedCodeDraftSource",
            "generatedCodeDraftPromptPreviewArtifactId",
            "codeDraftRequestState",
            "generatedCodeDraftWriteReport",
            "sourceNavigationState",
            "snapshotRevision",
            "lastMessageType",
            "lastGraphSource",
        )
        private val WORKFLOW_COMPARE_KEYS = setOf(
            "draftPatchPreview",
            "canUndoDraftPatchApply",
            "lastAppliedDraftPatchSummary",
            "lastDraftPatchApplyResult",
            "auditResult",
            "auditRequestState",
            "diffReviewResult",
            "diffReviewRequestState",
            "graphBeautificationResult",
            "graphBeautificationRequestState",
            "mermaidIssues",
            "diffItems",
            "syncPreviewItems",
            "generationPlan",
            "generationPlanRequestState",
            "generatedCodeDrafts",
            "generatedCodeDraftWarnings",
            "generatedCodeDraftSource",
            "generatedCodeDraftPromptPreviewArtifactId",
            "codeDraftRequestState",
            "generatedCodeDraftWriteReport",
            "sourceNavigationState",
        )
        private val FEEDBACK_KEYS = setOf(
            "operationFeedback",
            "selectedNodeId",
            "snapshotRevision",
            "lastMessageType",
            "lastGraphSource",
        )
        private val FEEDBACK_COMPARE_KEYS = setOf(
            "operationFeedback",
            "selectedNodeId",
        )
    }
}
