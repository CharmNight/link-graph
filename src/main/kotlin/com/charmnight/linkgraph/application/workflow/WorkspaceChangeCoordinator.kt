package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument

/**
 * Coordinates workspace-graph writes with the analysis/request state that must be invalidated with them.
 */
internal class WorkspaceChangeCoordinator(
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    private val clearSubjectAnalysisCache: () -> Unit,
    private val invalidateAsyncRequests: () -> Unit,
) {
    fun resetWorkspaceGraphContext() {
        clearSubjectAnalysisCache()
        invalidateAsyncRequests()
    }

    fun invalidateRequests() {
        invalidateAsyncRequests()
    }

    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        syncBrowser: Boolean = true,
    ) {
        resetWorkspaceGraphContext()
        workspaceGraphCommitter.commitWorkspaceGraph(
            expectedSnapshotRevision = null,
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            syncBrowser = syncBrowser,
        )
    }
}
