package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.intellij.openapi.diagnostic.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P2-2 DraftPatchWorkflow 事务边界测试。
 *
 * 验证：当 emit 抛异常时（模拟 presenter / projector 内部错误），
 * workflow 不应向上传播异常，调用者应继续拿到 use case 的计算结果。
 *
 * 原因：use case 是纯计算，emit 失败时 result 丢失不算数据损坏，
 * 让异常上抛会让上游（命令路由 / Action）误以为整条命令失败，
 * 但事实上状态可能已经部分应用（presenter 中间步骤）。
 */
class DraftPatchWorkflowTransactionTest {
    private val logger: Logger = Logger.getInstance(DraftPatchWorkflowTransactionTest::class.java)
    private val baseGraph = GraphDocument(
        nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "old")),
    )
    private val patch = GraphPatch(
        summary = "add node",
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

    @Test
    fun applyDraftPatchPreviewReturnsResultEvenWhenEventSinkThrows() {
        val snapshotProvider = ApplicationSnapshotProvider {
            ApplicationSnapshot(workspaceGraph = baseGraph, draftPatchPreview = patch)
        }
        val failingSink = GraphEditorApplicationEventSink { _ ->
            throw IllegalStateException("presenter boom")
        }

        val workflow = DraftPatchWorkflow(
            snapshotProvider = snapshotProvider,
            eventSink = failingSink,
            graphPatchApplyService = GraphPatchApplyService(),
            logger = logger,
        )

        // emit 抛异常时调用者不应看到异常，但应拿到 use case 计算出的 graph
        val resultGraph = workflow.applyDraftPatchPreview()
        assertNotNull(resultGraph)
        assertEquals(listOf("node-old", "node-new"), resultGraph.nodes.map { it.id })
    }

    @Test
    fun applyDraftPatchPreviewEmitsDraftPatchAppliedWithResult() {
        val snapshotProvider = ApplicationSnapshotProvider {
            ApplicationSnapshot(workspaceGraph = baseGraph, draftPatchPreview = patch)
        }
        val capturedEvents = mutableListOf<GraphEditorApplicationEvent>()
        val recordingSink = GraphEditorApplicationEventSink { event -> capturedEvents += event }

        val workflow = DraftPatchWorkflow(
            snapshotProvider = snapshotProvider,
            eventSink = recordingSink,
            graphPatchApplyService = GraphPatchApplyService(),
            logger = logger,
        )

        workflow.applyDraftPatchPreview()

        assertEquals(1, capturedEvents.size)
        val emitted = capturedEvents.first()
        assertTrue(emitted is GraphEditorApplicationEvent.DraftPatchApplied)
        val appliedPayload = assertIs<ApplyDraftPatchUseCaseResult.Applied>(emitted.result)
        assertEquals(listOf("node-old", "node-new"), appliedPayload.graph.nodes.map { it.id })
    }

    @Test
    fun clearDraftPatchPreviewSwallowsEmitFailureAndReturnsNormally() {
        val snapshotProvider = ApplicationSnapshotProvider {
            ApplicationSnapshot(workspaceGraph = baseGraph, draftPatchPreview = patch)
        }
        val failingSink = GraphEditorApplicationEventSink { _ ->
            throw IllegalStateException("presenter boom")
        }

        val workflow = DraftPatchWorkflow(
            snapshotProvider = snapshotProvider,
            eventSink = failingSink,
            graphPatchApplyService = GraphPatchApplyService(),
            logger = logger,
        )

        // 清除操作应正常返回，不传播异常
        workflow.clearDraftPatchPreview()
    }

    @Test
    fun restoreDraftPatchPreviewReturnsNullButDoesNotPropagateEmitFailure() {
        val snapshotProvider = ApplicationSnapshotProvider {
            // 没有 qaResult，use case 返回 MissingPreview
            ApplicationSnapshot(workspaceGraph = baseGraph, draftPatchPreview = patch)
        }
        val failingSink = GraphEditorApplicationEventSink { _ ->
            throw IllegalStateException("presenter boom")
        }

        val workflow = DraftPatchWorkflow(
            snapshotProvider = snapshotProvider,
            eventSink = failingSink,
            graphPatchApplyService = GraphPatchApplyService(),
            logger = logger,
        )

        // 没有 qaResult → use case 返回 MissingPreview，patch 是 null
        // 但 emit 仍会被调用一次（携带 MissingPreview 事件），emit 抛异常被吞掉
        val restoredPatch = workflow.restoreDraftPatchPreview(DraftPatchPreviewSource.QA)
        assertEquals(null, restoredPatch)
    }

    @Test
    fun undoLastDraftPatchApplySwallowsEmitFailure() {
        val snapshotProvider = ApplicationSnapshotProvider {
            // 没有 draftPatchUndo，use case 返回 MissingUndo
            ApplicationSnapshot(workspaceGraph = baseGraph)
        }
        val failingSink = GraphEditorApplicationEventSink { _ ->
            throw IllegalStateException("presenter boom")
        }

        val workflow = DraftPatchWorkflow(
            snapshotProvider = snapshotProvider,
            eventSink = failingSink,
            graphPatchApplyService = GraphPatchApplyService(),
            logger = logger,
        )

        // 没有 undo 记录时 use case 返回 MissingUndo，graph 是 null
        // emit 抛异常被吞掉，调用者拿到 null
        val undoneGraph = workflow.undoLastDraftPatchApply()
        assertEquals(null, undoneGraph)
    }
}
