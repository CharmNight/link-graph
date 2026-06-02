package com.charmnight.linkgraph.testing

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GenerationStatePresenter
import com.charmnight.linkgraph.ui.GraphEditorApplicationEventProjector
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.ReviewStatePresenter
import com.charmnight.linkgraph.ui.WorkspaceStatePresenter
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.charmnight.linkgraph.ui.withWorkspaceGraphChanged

internal fun GraphEditorStateService.editorSnapshotProvider(): EditorSnapshotProvider =
    EditorSnapshotProvider { snapshot().toWorkflowEditorSnapshot() }

internal fun GraphEditorStateService.toolGraphSnapshotProvider(): ToolGraphSnapshotProvider =
    ToolGraphSnapshotProvider { snapshot().toToolGraphSnapshot() }

internal fun GraphEditorStateService.workspaceGraphCommitter(
    requestBrowserSync: () -> Unit = {},
): WorkspaceGraphCommitter {
    return object : WorkspaceGraphCommitter {
        override fun commitWorkspaceGraph(
            expectedSnapshotRevision: Long?,
            graph: GraphDocument,
            selectedMethodSignature: String?,
            preserveDraftPatchUndo: Boolean,
            workingGraphDirty: Boolean,
            syncBrowser: Boolean,
        ): Boolean {
            val revision = expectedSnapshotRevision ?: snapshot().snapshotRevision
            val commitResult = tryCommit(revision) { current ->
                current.withWorkspaceGraphChanged(
                    graph = graph,
                    selectedMethodSignatureOverride = selectedMethodSignature,
                    preserveDraftPatchUndo = preserveDraftPatchUndo,
                    workingGraphDirtyOverride = workingGraphDirty,
                )
            }
            if (syncBrowser) {
                requestBrowserSync()
            }
            return commitResult.committed
        }
    }
}

internal fun GraphEditorStateService.workspaceStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> WorkspaceStatePresenter = {
    WorkspaceStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.reviewStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> ReviewStatePresenter = {
    ReviewStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.generationStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> GenerationStatePresenter = {
    GenerationStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.applicationEventSink(
    requestBrowserSync: () -> Unit = {},
): GraphEditorApplicationEventSink =
    GraphEditorApplicationEventProjector(
        stateService = this,
        requestBrowserSync = requestBrowserSync,
    ).eventSink()
