package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.LiveGraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.withWorkspaceGraphChanged

internal class ProjectEditorSession(
    private val stateService: GraphEditorStateService,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
    private val onBrowserSyncRequested: () -> Unit,
) {
    private val mutationContext: GraphEditorStateMutationContext = LiveGraphEditorStateMutationContext(stateService)

    fun snapshot(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = stateService.snapshot()

    fun mutate(
        syncBrowser: Boolean = true,
        action: GraphEditorStateMutationContext.() -> Unit,
    ) {
        val mutationStartedAt = System.nanoTime()
        mutationContext.action()
        traceStage(
            stage = "session.mutate",
            startedAtNanos = mutationStartedAt,
        ) {
            val snapshot = stateService.snapshot()
            listOf(
                "syncBrowser=$syncBrowser",
                "snapshotRevision=${snapshot.snapshotRevision}",
                "lastMessageType=${snapshot.lastMessageType}",
                "workspace=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceGraph)}",
            )
        }
        if (syncBrowser) {
            val syncStartedAt = System.nanoTime()
            onBrowserSyncRequested()
            traceStage(
                stage = "session.browserSyncRequested",
                startedAtNanos = syncStartedAt,
            ) {
                listOf("snapshotRevision=${stateService.snapshot().snapshotRevision}")
            }
        }
    }

    fun <T> mutateBatch(
        syncBrowser: Boolean = true,
        block: GraphEditorStateMutationContext.() -> T,
    ): T {
        val mutationStartedAt = System.nanoTime()
        val result = mutationContext.block()
        traceStage(
            stage = "session.mutateBatch",
            startedAtNanos = mutationStartedAt,
        ) {
            val snapshot = stateService.snapshot()
            listOf(
                "syncBrowser=$syncBrowser",
                "snapshotRevision=${snapshot.snapshotRevision}",
                "lastMessageType=${snapshot.lastMessageType}",
                "workspace=${LinkGraphRenderTrace.graphSummary(snapshot.workspaceGraph)}",
            )
        }
        if (syncBrowser) {
            val syncStartedAt = System.nanoTime()
            onBrowserSyncRequested()
            traceStage(
                stage = "session.browserSyncRequested",
                startedAtNanos = syncStartedAt,
            ) {
                listOf("snapshotRevision=${stateService.snapshot().snapshotRevision}")
            }
        }
        return result
    }

    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        expectedRevision: Long? = null,
        syncBrowser: Boolean = true,
    ): Boolean {
        val mutationStartedAt = System.nanoTime()
        val commitResult = stateService.tryCommit(expectedRevision ?: stateService.snapshot().snapshotRevision) { current ->
            current.withWorkspaceGraphChanged(
                graph = graph,
                selectedMethodSignatureOverride = selectedMethodSignature,
                preserveDraftPatchUndo = preserveDraftPatchUndo,
                workingGraphDirtyOverride = workingGraphDirty,
            )
        }
        traceStage(
            stage = "session.markGraphChanged",
            startedAtNanos = mutationStartedAt,
        ) {
            listOf(
                "committed=${commitResult.committed}",
                "syncBrowser=$syncBrowser",
                "expectedRevision=${expectedRevision ?: "current"}",
                "snapshotRevision=${commitResult.snapshot.snapshotRevision}",
                "workspace=${LinkGraphRenderTrace.graphSummary(commitResult.snapshot.workspaceGraph)}",
            )
        }
        if (syncBrowser) {
            val syncStartedAt = System.nanoTime()
            onBrowserSyncRequested()
            traceStage(
                stage = "session.browserSyncRequested",
                startedAtNanos = syncStartedAt,
            ) {
                listOf("snapshotRevision=${stateService.snapshot().snapshotRevision}")
            }
        }
        return commitResult.committed
    }

    fun markRuntimeArtifactSummaries(
        scene: String,
        summaries: List<com.charmnight.linkgraph.ui.RuntimeArtifactSummary>,
        syncBrowser: Boolean = true,
    ) {
        val mutationStartedAt = System.nanoTime()
        stateService.workbench.markRuntimeArtifactSummaries(scene, summaries)
        traceStage(
            stage = "session.markRuntimeArtifactSummaries",
            startedAtNanos = mutationStartedAt,
        ) {
            listOf(
                "scene=$scene",
                "summaries=${summaries.size}",
                "syncBrowser=$syncBrowser",
                "snapshotRevision=${stateService.snapshot().snapshotRevision}",
            )
        }
        if (syncBrowser) {
            val syncStartedAt = System.nanoTime()
            onBrowserSyncRequested()
            traceStage(
                stage = "session.browserSyncRequested",
                startedAtNanos = syncStartedAt,
            ) {
                listOf("snapshotRevision=${stateService.snapshot().snapshotRevision}")
            }
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
