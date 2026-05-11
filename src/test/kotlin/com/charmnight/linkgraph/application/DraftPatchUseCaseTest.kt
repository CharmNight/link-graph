package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.DraftPatchUndo
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.DraftPatchUseCase
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewSource
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DraftPatchUseCaseTest {
    @Test
    fun appliesSelectedDraftPatchOperationsWithoutMutatingSnapshot() {
        val baseGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "old")),
        )
        val patch = GraphPatch(
            summary = "add node",
            operations = listOf(
                GraphPatchOperation(
                    id = "op-add",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "node-new",
                    node = GraphNode(id = "node-new", type = NodeType.METHOD, title = "new"),
                ),
                GraphPatchOperation(
                    id = "op-skip",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "node-skip",
                    node = GraphNode(id = "node-skip", type = NodeType.METHOD, title = "skip"),
                ),
            ),
        )
        val snapshot = ApplicationSnapshot(workspaceGraph = baseGraph, draftPatchPreview = patch)

        val result = DraftPatchUseCase(GraphPatchApplyService()).applyDraftPatchPreview(snapshot, setOf("op-add"))

        val applied = assertIs<ApplyDraftPatchUseCaseResult.Applied>(result)
        assertEquals(listOf("node-old", "node-new"), applied.graph.nodes.map { it.id })
        assertEquals(1, applied.applyResult.appliedOperationCount)
        assertEquals(listOf("node-new"), applied.applyResult.appliedNodeIds)
        assertEquals(patch, snapshot.draftPatchPreview)
    }

    @Test
    fun restoreDraftPatchPreviewSelectsRequestedSource() {
        val qaPatch = GraphPatch(summary = "qa")
        val diffPatch = GraphPatch(summary = "diff")
        val lastPatch = GraphPatch(summary = "last")
        val snapshot = ApplicationSnapshot(
            qaResult = GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "q",
                answer = "a",
                promptPreview = "p",
                patch = qaPatch,
            ),
            diffReviewResult = GraphPatchResult(
                source = LlmResultSource.LOCAL_RULE,
                question = "q",
                answer = "a",
                promptPreview = "p",
                patch = diffPatch,
            ),
            draftPatchUndo = DraftPatchUndo(graphBeforeApply = GraphDocument(), patchPreview = lastPatch),
        )

        val result = DraftPatchUseCase(GraphPatchApplyService())
            .restoreDraftPatchPreview(snapshot, RestoreDraftPatchPreviewSource.DIFF_REVIEW)

        val restored = assertIs<RestoreDraftPatchPreviewUseCaseResult.Restored>(result)
        assertEquals(diffPatch, restored.patch)
    }

    @Test
    fun undoDraftPatchApplyReturnsPreviousGraphAndPreview() {
        val previousGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "node-before", type = NodeType.METHOD, title = "before")),
        )
        val preview = GraphPatch(summary = "last")

        val result = DraftPatchUseCase(GraphPatchApplyService()).undoLastDraftPatchApply(
            ApplicationSnapshot(draftPatchUndo = DraftPatchUndo(previousGraph, preview)),
        )

        val undone = assertIs<UndoDraftPatchApplyUseCaseResult.Undone>(result)
        assertEquals(previousGraph, undone.graph)
        assertEquals(preview, undone.patchPreview)
    }
}
