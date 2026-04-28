package com.charmnight.linkgraph.ui

import com.intellij.ui.jcef.JBCefBrowser

internal class GraphBrowserTransportDispatcher(
    private val browserProvider: () -> JBCefBrowser?,
    private val bridge: GraphEditorBridge,
    private val sliceRenderer: GraphEditorTransportSliceRenderer,
    private val transportState: GraphBrowserTransportState,
    private val debugTracingEnabled: Boolean,
    private val browserLoadedProvider: () -> Boolean,
    private val pendingSnapshotConsumer: (Long) -> GraphEditorStateSnapshot?,
    private val lastDispatchedSnapshotUpdater: (GraphEditorStateSnapshot) -> Unit,
) {
    fun executeSnapshotScript(
        transport: GraphBrowserTransportState.DispatchedTransport?,
        reason: String,
    ) {
        val currentBrowser = browserProvider() ?: return
        val dispatchedTransport = transport ?: return
        if (dispatchedTransport.script.isBlank()) {
            return
        }
        pendingSnapshotConsumer(dispatchedTransport.revision)?.let(lastDispatchedSnapshotUpdater)
        currentBrowser.cefBrowser.executeJavaScript(
            dispatchedTransport.script,
            currentBrowser.cefBrowser.url,
            0,
        )
        scheduleRuntimeProbe(reason)
    }

    fun dispatchArtifactSlice(artifactIds: List<String>) {
        val currentBrowser = browserProvider() ?: return
        if (artifactIds.isEmpty()) {
            return
        }
        val artifactContents = sliceRenderer.artifactContents(artifactIds)
        if (artifactContents.isEmpty()) {
            return
        }
        val currentSnapshot = bridge.currentState()
        val script = sliceRenderer.renderScript(
            listOf(
                GraphEditorTransportEnvelope.ArtifactSlice(
                    sessionId = transportState.sessionId,
                    revision = currentSnapshot.snapshotRevision,
                    state = linkedMapOf(
                        "artifactContents" to artifactContents,
                        "snapshotRevision" to currentSnapshot.snapshotRevision,
                        "lastMessageType" to "artifactSlice",
                    ),
                ),
            ),
        )
        currentBrowser.cefBrowser.executeJavaScript(script, currentBrowser.cefBrowser.url, 0)
    }

    private fun scheduleRuntimeProbe(reason: String) {
        if (!debugTracingEnabled) {
            return
        }
        val currentBrowser = browserProvider() ?: return
        if (!browserLoadedProvider()) {
            return
        }
        currentBrowser.cefBrowser.executeJavaScript(
            GraphBrowserDebugProbe.buildRuntimeProbeScript(reason),
            currentBrowser.cefBrowser.url,
            0,
        )
    }
}
