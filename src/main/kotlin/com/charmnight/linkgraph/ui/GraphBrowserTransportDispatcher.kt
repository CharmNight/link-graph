package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.services.LinkGraphRenderTrace
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
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
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
        val executeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(
            dispatchedTransport.script,
            currentBrowser.cefBrowser.url,
            0,
        )
        traceStage(
            stage = "transport.executeJavaScript",
            startedAtNanos = executeStartedAt,
        ) {
            listOf(
                "reason=$reason",
                "revision=${dispatchedTransport.revision}",
                "scriptChars=${dispatchedTransport.script.length}",
                "browserLoaded=${browserLoadedProvider()}",
            )
        }
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
        val renderStartedAt = System.nanoTime()
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
        traceStage(
            stage = "transport.artifactSlice.render",
            startedAtNanos = renderStartedAt,
        ) {
            listOf(
                "requested=${artifactIds.size}",
                "found=${artifactContents.size}",
                "revision=${currentSnapshot.snapshotRevision}",
                "scriptChars=${script.length}",
            )
        }
        val executeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(script, currentBrowser.cefBrowser.url, 0)
        traceStage(
            stage = "transport.artifactSlice.executeJavaScript",
            startedAtNanos = executeStartedAt,
        ) {
            listOf(
                "found=${artifactContents.size}",
                "revision=${currentSnapshot.snapshotRevision}",
                "scriptChars=${script.length}",
            )
        }
    }

    private fun scheduleRuntimeProbe(reason: String) {
        if (!debugTracingEnabled) {
            return
        }
        val currentBrowser = browserProvider() ?: return
        if (!browserLoadedProvider()) {
            return
        }
        val probeStartedAt = System.nanoTime()
        currentBrowser.cefBrowser.executeJavaScript(
            GraphBrowserDebugProbe.buildRuntimeProbeScript(reason),
            currentBrowser.cefBrowser.url,
            0,
        )
        traceStage(
            stage = "transport.runtimeProbe.schedule",
            startedAtNanos = probeStartedAt,
        ) {
            listOf("reason=$reason")
        }
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
}
