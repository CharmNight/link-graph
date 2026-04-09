package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphEditorTransportSliceRendererTest {
    @Test
    fun feedbackOnlyUpdateDoesNotResendSemanticGraphSlice() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(
            snapshotRevision = 1,
            semanticRevision = 1,
            layoutRevision = 1,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\nclass OrderDraftDto {}",
                ),
            ),
        )
        val current = previous.copy(
            snapshotRevision = 2,
            operationFeedback = GraphEditorStateService.OperationFeedback(
                level = GraphEditorStateService.OperationFeedbackLevel.INFO,
                message = "只更新提示文案，不应重发语义图。",
            ),
            lastMessageType = "operationFeedback",
        )

        val envelopes = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertEquals(listOf(GraphEditorTransportEnvelope.FeedbackSlice::class), envelopes.map { it::class })
        val script = renderer.renderScript(envelopes)
        assertTrue(script.contains("只更新提示文案，不应重发语义图。"))
        assertFalse(script.contains("OrderController.submit"))
        assertFalse(script.contains("package com.example"))
    }

    @Test
    fun layoutOnlyUpdateDoesNotResendSemanticGraphSlice() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(
            snapshotRevision = 4,
            semanticRevision = 3,
            layoutRevision = 1,
        )
        val current = previous.copy(
            snapshotRevision = 5,
            layoutRevision = 2,
            layoutState = GraphLayoutState(
                positions = mapOf(
                    "method:submit-order" to GraphLayoutPosition(640.0, 320.0),
                ),
            ),
            lastMessageType = "layoutChanged",
        )

        val envelopes = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertEquals(listOf(GraphEditorTransportEnvelope.LayoutSlice::class), envelopes.map { it::class })
        val script = renderer.renderScript(envelopes)
        assertTrue(script.contains("\"type\":\"LAYOUT_SLICE\""))
        assertTrue(script.contains("640.0") || script.contains("640"))
        assertFalse(script.contains("OrderController.submit"))
    }

    @Test
    fun bootstrapInitStillContainsEnoughStateToRenderWithoutSecondRoundTrip() {
        val renderer = GraphEditorTransportSliceRenderer()
        val bootstrapScript = renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = snapshot(snapshotRevision = 7),
        )

        assertTrue(bootstrapScript.contains("\"type\":\"BOOTSTRAP_INIT\""))
        assertTrue(bootstrapScript.contains("OrderController.submit"))
        assertTrue(bootstrapScript.contains("\"visibleGraph\""))
        assertTrue(bootstrapScript.contains("\"workingGraph\""))
    }

    @Test
    fun workflowTransportExternalizesLargeArtifactsInsteadOfEmbeddingRawDraftAndPromptContent() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(snapshotRevision = 10)
        val current = previous.copy(
            snapshotRevision = 11,
            generationPlan = GenerationPlan(
                source = com.charmnight.linkgraph.llm.GenerationPlanSource.REMOTE,
                summary = "补齐 DTO 与 service 接线",
                items = listOf(
                    GenerationPlanItem(
                        id = "plan-1",
                        title = "新增 DTO",
                        description = "生成 OrderDraftDto 并接回 service。",
                        risk = SyncPreviewRisk.MEDIUM,
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    ),
                ),
                warnings = listOf("请复核字段命名"),
                promptPreview = "system: generate plan\nuser: inspect graph",
            ),
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class OrderDraftDto {}",
                    warnings = listOf("保留 Lombok 与否需要人工确认"),
                ),
            ),
            generatedCodeDraftPromptPreview = "system: generate code\nuser: produce DTO draft",
            lastMessageType = "requestCodeDrafts",
        )

        val envelopes = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        val workflowEnvelope = envelopes.singleOrNull {
            it is GraphEditorTransportEnvelope.WorkflowSlice
        } as? GraphEditorTransportEnvelope.WorkflowSlice
        assertNotNull(workflowEnvelope)

        val draftPayload = (workflowEnvelope.state["generatedCodeDrafts"] as? List<*>)?.singleOrNull() as? Map<*, *>
        assertNotNull(draftPayload)
        assertFalse(draftPayload.containsKey("content"))
        assertTrue(draftPayload["contentArtifactId"].toString().isNotBlank())

        val generationPlan = workflowEnvelope.state["generationPlan"] as? Map<*, *>
        assertNotNull(generationPlan)
        assertFalse(generationPlan.containsKey("promptPreview"))
        assertTrue(generationPlan["promptPreviewArtifactId"].toString().isNotBlank())

        assertFalse(workflowEnvelope.state.containsKey("generatedCodeDraftPromptPreview"))
        assertTrue(workflowEnvelope.state["generatedCodeDraftPromptPreviewArtifactId"].toString().isNotBlank())

        val script = renderer.renderScript(envelopes)
        assertFalse(script.contains("public class OrderDraftDto"))
        assertFalse(script.contains("system: generate code"))
        assertFalse(script.contains("system: generate plan"))
    }

    private fun snapshot(
        snapshotRevision: Long,
        semanticRevision: Long = 0,
        layoutRevision: Long = 0,
        generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    ): GraphEditorStateService.Snapshot {
        return GraphEditorStateService.Snapshot(
            visibleGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            semanticRevision = semanticRevision,
            layoutRevision = layoutRevision,
            snapshotRevision = snapshotRevision,
            generatedCodeDrafts = generatedCodeDrafts,
        )
    }
}
