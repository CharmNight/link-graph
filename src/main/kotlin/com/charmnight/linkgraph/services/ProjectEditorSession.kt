package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.LiveGraphEditorStateMutationContext
import com.charmnight.linkgraph.ui.withWorkspaceGraphChanged

internal class ProjectEditorSession(
    private val stateService: GraphEditorStateService,
    private val onBrowserSyncRequested: () -> Unit,
) {
    private val mutationContext: GraphEditorStateMutationContext = LiveGraphEditorStateMutationContext(stateService)

    fun snapshot(): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot = stateService.snapshot()

    fun mutate(
        syncBrowser: Boolean = true,
        action: GraphEditorStateMutationContext.() -> Unit,
    ) {
        mutationContext.action()
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }

    fun <T> mutateBatch(
        syncBrowser: Boolean = true,
        block: GraphEditorStateMutationContext.() -> T,
    ): T {
        val result = mutationContext.block()
        if (syncBrowser) {
            onBrowserSyncRequested()
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
        val commitResult = stateService.tryCommit(expectedRevision ?: stateService.snapshot().snapshotRevision) { current ->
            current.withWorkspaceGraphChanged(
                graph = graph,
                selectedMethodSignatureOverride = selectedMethodSignature,
                preserveDraftPatchUndo = preserveDraftPatchUndo,
                workingGraphDirtyOverride = workingGraphDirty,
            )
        }
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
        return commitResult.committed
    }

    fun markRuntimeArtifactSummaries(
        scene: String,
        summaries: List<com.charmnight.linkgraph.ui.RuntimeArtifactSummary>,
        syncBrowser: Boolean = true,
    ) {
        stateService.workbench.markRuntimeArtifactSummaries(scene, summaries)
        if (syncBrowser) {
            onBrowserSyncRequested()
        }
    }
}
