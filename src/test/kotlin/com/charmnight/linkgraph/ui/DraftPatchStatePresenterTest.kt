package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchApplySummary
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftPatchStatePresenterTest {
    @Test
    fun mapsPreviewResultToPatchAndFeedbackState() {
        val stateService = GraphEditorStateService()
        val presenter = DraftPatchStatePresenter(stateService)
        val patch = GraphPatch(summary = "preview summary")

        presenter.presentPreview(PreviewDraftPatchUseCaseResult.Previewed(patch))

        val snapshot = stateService.snapshot()
        assertEquals(patch, snapshot.draftPatchPreview)
        assertEquals(ApplicationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
        assertEquals("preview summary", snapshot.operationFeedback?.message)
    }

    @Test
    fun mapsAppliedResultToUndoGraphAndApplySummaryState() {
        val stateService = GraphEditorStateService()
        val presenter = DraftPatchStatePresenter(stateService)
        val baseGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "old")),
        )
        val appliedGraph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "node-old", type = NodeType.METHOD, title = "old"),
                GraphNode(id = "node-new", type = NodeType.METHOD, title = "new"),
            ),
        )
        val patch = GraphPatch(
            operations = listOf(
                GraphPatchOperation(
                    id = "op-add",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "node-new",
                    node = GraphNode(id = "node-new", type = NodeType.METHOD, title = "new"),
                ),
            ),
        )

        presenter.presentApply(
            ApplyDraftPatchUseCaseResult.Applied(
                graphBeforeApply = baseGraph,
                patch = patch,
                graph = appliedGraph,
                applyResult = DraftPatchApplySummary(
                    summary = "已应用 1 条草稿图变更。",
                    appliedOperationCount = 1,
                    appliedNodeIds = listOf("node-new"),
                    focusNodeId = "node-new",
                ),
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(appliedGraph, snapshot.workspaceGraph)
        assertEquals(baseGraph, snapshot.draftPatchUndoState?.graphBeforeApply)
        assertEquals(null, snapshot.draftPatchPreview)
        assertEquals(1, snapshot.lastDraftPatchApplyResult?.appliedOperationCount)
        assertEquals(ApplicationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsUndoResultToPreviousGraphAndRestoredPreview() {
        val stateService = GraphEditorStateService()
        val presenter = DraftPatchStatePresenter(stateService)
        val previousGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-before", type = NodeType.METHOD, title = "before")),
        )
        val preview = GraphPatch(summary = "previous patch")

        presenter.presentUndo(UndoDraftPatchApplyUseCaseResult.Undone(previousGraph, preview))

        val snapshot = stateService.snapshot()
        assertEquals(previousGraph, snapshot.workspaceGraph)
        assertEquals(null, snapshot.draftPatchUndoState)
        assertEquals(preview, snapshot.draftPatchPreview)
        assertEquals("undoDraftPatchApply", snapshot.lastMessageType)
    }
}
