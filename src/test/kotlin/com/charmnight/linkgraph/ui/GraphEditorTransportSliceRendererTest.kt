package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.EditScope
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
    fun anyStateUpdateIsRenderedAsSingleFullSnapshotEnvelope() {
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
            operationFeedback = com.charmnight.linkgraph.ui.OperationFeedback(
                level = com.charmnight.linkgraph.ui.OperationFeedbackLevel.INFO,
                message = "只更新提示文案，也要通过完整权威快照下发。",
            ),
            lastMessageType = "operationFeedback",
        )

        val envelopes = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertEquals(1, envelopes.size)
        val script = renderer.renderScript(envelopes)
        assertTrue(script.contains("只更新提示文案，也要通过完整权威快照下发。"))
        assertTrue(script.contains("OrderController.submit"))
        assertTrue(script.contains("contentArtifactId"))
        assertFalse(script.contains("\"type\":\"FEEDBACK_SLICE\""))
    }

    @Test
    fun bootstrapInitStillContainsEnoughStateToRenderWithoutSecondRoundTrip() {
        val renderer = GraphEditorTransportSliceRenderer()
        val bootstrapScript = renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = snapshot(snapshotRevision = 7),
        )

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

        val snapshotEnvelope = envelopes.singleOrNull()
        assertNotNull(snapshotEnvelope)

        val draftPayload = (snapshotEnvelope.state["generatedCodeDrafts"] as? List<*>)?.singleOrNull() as? Map<*, *>
        assertNotNull(draftPayload)
        assertFalse(draftPayload.containsKey("content"))
        assertTrue(draftPayload["contentArtifactId"].toString().isNotBlank())

        val generationPlan = snapshotEnvelope.state["generationPlan"] as? Map<*, *>
        assertNotNull(generationPlan)
        assertFalse(generationPlan.containsKey("promptPreview"))
        assertTrue(generationPlan["promptPreviewArtifactId"].toString().isNotBlank())

        assertFalse(snapshotEnvelope.state.containsKey("generatedCodeDraftPromptPreview"))
        assertTrue(snapshotEnvelope.state["generatedCodeDraftPromptPreviewArtifactId"].toString().isNotBlank())

        val script = renderer.renderScript(envelopes)
        assertFalse(script.contains("public class OrderDraftDto"))
        assertFalse(script.contains("system: generate code"))
        assertFalse(script.contains("system: generate plan"))
    }

    @Test
    fun bootstrapInitAllowsStructuredExistingFileDraftWithoutInlineContentArtifact() {
        val renderer = GraphEditorTransportSliceRenderer()
        val bootstrapScript = renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = snapshot(
                snapshotRevision = 12,
                generatedCodeDrafts = listOf(
                    GeneratedCodeDraft(
                        id = "draft-1",
                        sourceNodeId = "class:order-draft-dto",
                        title = "OrderDraftDto.java",
                        targetPath = "src/main/java/com/example/OrderDraftDto.java",
                        content = "package com.example;\npublic class OrderDraftDto {}",
                    ),
                    GeneratedCodeDraft(
                        id = "draft-2",
                        sourceNodeId = "method:submit-order",
                        title = "OrderController.java",
                        targetPath = "src/main/java/com/example/OrderController.java",
                        editOperations = listOf(
                            CodeEditOperation(
                                operationId = "edit-1",
                                filePath = "src/main/java/com/example/OrderController.java",
                                scopeId = "scope-submit-order",
                                kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                                payload = "public SubmitResult submit(String request) {\n    return fallback(request);\n}",
                            ),
                        ),
                        editScopes = listOf(
                            EditScope(
                                scopeId = "scope-submit-order",
                                targetNodeId = "method:submit-order",
                                filePath = "src/main/java/com/example/OrderController.java",
                                language = "JAVA",
                                symbolKind = "METHOD",
                                symbolSignature = "com.example.OrderController#submit(java.lang.String)",
                                startLine = 18,
                                endLine = 27,
                                allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(bootstrapScript.contains("\"generatedCodeDrafts\""))
        assertTrue(bootstrapScript.contains("\"contentArtifactId\""))
        assertTrue(bootstrapScript.contains("\"kind\":\"REPLACE_METHOD_BLOCK\""))
        assertTrue(bootstrapScript.contains("scope-submit-order"))
        assertFalse(bootstrapScript.contains("\"contentArtifactId\":\"draft-content:draft-2"))
    }

    private fun snapshot(
        snapshotRevision: Long,
        semanticRevision: Long = 0,
        layoutRevision: Long = 0,
        generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    ): com.charmnight.linkgraph.ui.GraphEditorStateSnapshot {
        return com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
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
