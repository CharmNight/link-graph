package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorSyncNotifier
import com.charmnight.linkgraph.ui.toToolGraphSnapshot
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
internal class GraphEditorApplicationProjectionService(
    private val project: Project,
) : GraphEditorPresentationProvider {
    private val stateService: GraphEditorStateService
        get() = project.getService(GraphEditorStateService::class.java)

    private val syncNotifier: GraphEditorSyncNotifier
        get() = project.getService(GraphEditorSyncNotifier::class.java)

    private val riskResolutionService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RiskResolutionService()
    }

    override fun editorSnapshotProvider(): EditorSnapshotProvider =
        EditorSnapshotProvider { stateService.snapshot().toWorkflowEditorSnapshot() }

    override fun applicationSnapshotProvider(): ApplicationSnapshotProvider =
        ApplicationSnapshotProvider { stateService.snapshot().toApplicationSnapshot() }

    override fun toolGraphSnapshotProvider(): ToolGraphSnapshotProvider =
        ToolGraphSnapshotProvider { stateService.snapshot().toToolGraphSnapshot() }

    override fun workspaceGraphCommitter(): WorkspaceGraphCommitter {
        return object : WorkspaceGraphCommitter {
            override fun commitWorkspaceGraph(
                expectedSnapshotRevision: Long?,
                graph: GraphDocument,
                selectedMethodSignature: String?,
                preserveDraftPatchUndo: Boolean,
                workingGraphDirty: Boolean,
                syncBrowser: Boolean,
                graphEditTransaction: GraphEditTransaction?,
            ): Boolean {
                val currentStateService = stateService
                val revision = expectedSnapshotRevision ?: currentStateService.snapshot().snapshotRevision
                val commitResult = currentStateService.tryCommit(revision) { current ->
                    current.withWorkspaceGraphChanged(
                        graph = graph,
                        selectedMethodSignatureOverride = selectedMethodSignature,
                        preserveDraftPatchUndo = preserveDraftPatchUndo,
                        workingGraphDirtyOverride = workingGraphDirty,
                        graphEditTransaction = graphEditTransaction,
                    )
                }
                if (syncBrowser) {
                    syncNotifier.requestSync()
                }
                return commitResult.committed
            }
        }
    }

    override fun eventSink(): GraphEditorApplicationEventSink =
        GraphEditorApplicationEventProjector(
            stateService = stateService,
            requestBrowserSync = syncNotifier::requestSync,
            riskResolutionService = riskResolutionService,
        ).eventSink()
}
