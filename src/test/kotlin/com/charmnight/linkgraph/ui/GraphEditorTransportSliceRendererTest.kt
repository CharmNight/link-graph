package com.charmnight.linkgraph.ui
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanItem
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
    fun operationFeedbackUpdateIsRenderedAsFeedbackSliceInsteadOfFullSnapshot() {
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
                level = com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.INFO,
                message = "只更新提示文案，不应通过完整权威快照下发。",
            ),
            lastMessageType = "operationFeedback",
        )

        val envelopes = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertEquals(1, envelopes.size)
        val feedbackEnvelope = envelopes.single()
        assertEquals("FEEDBACK_SLICE", feedbackEnvelope.transportType)
        val feedbackState = assertNotNull(feedbackEnvelope.state as? FeedbackSlicePayloadDto)
        assertEquals(
            "只更新提示文案，不应通过完整权威快照下发。",
            feedbackState.operationFeedback?.message,
        )
        // FeedbackSlicePayloadDto 不携带 workspaceGraph / generatedCodeDrafts 字段
        val script = renderer.renderScript(envelopes)
        assertTrue(script.contains("只更新提示文案，不应通过完整权威快照下发。"))
        assertFalse(script.contains("OrderController.submit"))
        assertFalse(script.contains("contentArtifactId"))
        assertTrue(script.contains("\"type\":\"FEEDBACK_SLICE\""))
    }

    @Test
    fun bootstrapInitStillContainsEnoughStateToRenderWithoutSecondRoundTrip() {
        val renderer = GraphEditorTransportSliceRenderer()
        val bootstrapScript = renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = snapshot(snapshotRevision = 7),
        )

        assertTrue(bootstrapScript.contains("OrderController.submit"))
        assertTrue(bootstrapScript.contains("\"workspaceGraph\""))
        assertTrue(bootstrapScript.contains("\"workspaceBaseGraph\""))
        assertTrue(bootstrapScript.contains("\"sceneStates\""))
    }

    @Test
    fun feedbackSliceIncrementalScriptDoesNotRebuildFullPayload() {
        val traceMessages = mutableListOf<String>()
        val renderer = GraphEditorTransportSliceRenderer(
            runtimeTrace = { message -> traceMessages += message() },
        )
        val previous = snapshot(snapshotRevision = 20)
        val current = previous.copy(
            snapshotRevision = 21,
            operationFeedback = com.charmnight.linkgraph.ui.OperationFeedback(
                level = com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.INFO,
                message = "trace me",
            ),
            lastMessageType = "operationFeedback",
        )

        val script = renderer.renderIncrementalScript(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertNotNull(script)
        assertFalse(traceMessages.any { it.contains("stage=transport.payload.current") })
        assertFalse(traceMessages.any { it.contains("stage=transport.payload.previous") })
        assertTrue(traceMessages.any { it.contains("stage=transport.renderScript") })
        assertTrue(traceMessages.any { it.contains("scriptChars=") })
        assertTrue(script.contains("\"type\":\"FEEDBACK_SLICE\""))
    }

    @Test
    fun feedbackSliceCommitDoesNotReplaceCommittedFullPayloadHash() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(snapshotRevision = 40)
        renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = previous,
        )
        val committedFullPayloadHash = renderer.lastRenderedPayloadHashForTest()
        assertNotNull(committedFullPayloadHash)
        val current = previous.copy(
            snapshotRevision = 41,
            operationFeedback = com.charmnight.linkgraph.ui.OperationFeedback(
                level = com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel.INFO,
                message = "只更新反馈 slice。",
            ),
            lastMessageType = "operationFeedback",
        )

        val rendered = renderer.renderIncrementalSnapshotScript(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )
        assertNotNull(rendered)
        assertEquals("FEEDBACK_SLICE", rendered.envelopes.single().transportType)
        renderer.commitRenderedSnapshot(rendered)

        assertEquals(committedFullPayloadHash, renderer.lastRenderedPayloadHashForTest())
    }

    @Test
    fun workflowTransportExternalizesLargeArtifactsInsteadOfEmbeddingRawDraftAndPromptContent() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(snapshotRevision = 10)
        val current = previous.copy(
            snapshotRevision = 11,
            generationPlan = GenerationPlan(
                source = com.charmnight.linkgraph.agent.model.GenerationPlanSource.REMOTE,
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
        val snapshotState = assertNotNull(snapshotEnvelope.state as? BootstrapPayloadDto)

        val draftPayload = assertNotNull(snapshotState.generatedCodeDrafts.singleOrNull())
        // P2-6: content 外化到 artifact，DTO 的 content 字段为 null（contentArtifactId 不为空时不渲染 content）
        assertTrue(draftPayload.content == null)
        assertTrue(draftPayload.contentArtifactId?.isNotBlank() == true)

        val generationPlan = assertNotNull(snapshotState.generationPlan)
        // P2-6: generationPlan.promptPreview 外化到 artifact，DTO 不再有此字段
        assertTrue(generationPlan.promptPreviewArtifactId?.isNotBlank() == true)

        // P2-6: BootstrapPayloadDto 不再有 generatedCodeDraftPromptPreview 字段（只有 artifactId）
        assertTrue(snapshotState.generatedCodeDraftPromptPreviewArtifactId?.isNotBlank() == true)

        val script = renderer.renderScript(envelopes)
        assertFalse(script.contains("public class OrderDraftDto"))
        assertFalse(script.contains("system: generate code"))
        assertFalse(script.contains("system: generate plan"))
    }

    @Test
    fun snapshotArtifactsCanBePreparedWithoutRenderingDiscardedBootstrapScript() {
        val renderer = GraphEditorTransportSliceRenderer()
        val pageRenderer = GraphEditorPageRenderer()
        val current = snapshot(
            snapshotRevision = 12,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class OrderDraftDto {}",
                ),
            ),
        )

        val artifactRefs = renderer.prepareSnapshotArtifacts(current)
        val artifactId = assertNotNull(artifactRefs.generatedCodeDraftContentArtifactIds["draft-1"])
        val rendered = pageRenderer.render(
            entryHtml = "<html><head></head><body><div id=\"root\"></div></body></html>",
            sessionId = "session-1",
            snapshot = current,
            artifactRefs = artifactRefs,
            darkTheme = true,
        )

        assertFalse(rendered.contains("public class OrderDraftDto"))
        assertTrue(rendered.contains(artifactId))
        assertEquals(
            "package com.example;\npublic class OrderDraftDto {}",
            renderer.artifactContents(listOf(artifactId))[artifactId],
        )
    }

    @Test
    fun incrementalRenderDoesNotReplaceCommittedArtifactsBeforeDispatchCommit() {
        val renderer = GraphEditorTransportSliceRenderer()
        val previous = snapshot(
            snapshotRevision = 30,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class OldDraftDto {}",
                ),
            ),
        )
        renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = previous,
        )
        val previousArtifactId = assertNotNull(
            renderer.currentArtifactRefs().generatedCodeDraftContentArtifactIds["draft-1"],
        )
        val current = previous.copy(
            snapshotRevision = 31,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class NewDraftDto {}",
                ),
            ),
        )

        renderer.renderIncrementalScript(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertEquals(
            previousArtifactId,
            renderer.currentArtifactRefs().generatedCodeDraftContentArtifactIds["draft-1"],
        )
        assertEquals(
            "package com.example;\npublic class OldDraftDto {}",
            renderer.artifactContents(listOf(previousArtifactId))[previousArtifactId],
        )
    }

    @Test
    fun artifactOnlyDraftContentUpdateIsRenderedAsArtifactSliceWithoutFullPayloadHash() {
        val traceMessages = mutableListOf<String>()
        val renderer = GraphEditorTransportSliceRenderer(
            runtimeTrace = { message -> traceMessages += message() },
        )
        val previous = snapshot(
            snapshotRevision = 50,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class OldDraftDto {}",
                ),
            ),
        )
        renderer.renderBootstrapInitScript(
            sessionId = "session-1",
            snapshot = previous,
        )
        traceMessages.clear()
        val current = previous.copy(
            snapshotRevision = 51,
            generatedCodeDrafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class NewDraftDto {}",
                ),
            ),
        )

        val rendered = renderer.renderIncrementalSnapshotScript(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        )

        assertNotNull(rendered)
        val artifactEnvelope = rendered.envelopes.single()
        assertEquals("ARTIFACT_SLICE", artifactEnvelope.transportType)
        val artifactState = assertNotNull(artifactEnvelope.state as? ArtifactSlicePayloadDto)
        val draftPayload = assertNotNull(artifactState.generatedCodeDrafts?.singleOrNull())
        assertEquals("draft-1", draftPayload.id)
        assertTrue(draftPayload.contentArtifactId?.contains("NewDraftDto")?.not() == true)
        // P2-6: content 外化到 artifact，DTO 的 content 字段为 null
        assertTrue(draftPayload.content == null)
        val artifactContents = artifactState.artifactContents
        assertEquals(1, artifactContents.size)
        assertEquals(draftPayload.contentArtifactId, artifactContents.keys.single())
        assertEquals("package com.example;\npublic class NewDraftDto {}", artifactContents.values.single())
        assertFalse(traceMessages.any { it.contains("stage=transport.payload.current") })
        assertFalse(traceMessages.any { it.contains("stage=transport.payload.compare") })
    }

    @Test
    fun assistantHistoryExternalizesGenerationAndCodeDraftArtifacts() {
        val renderer = GraphEditorTransportSliceRenderer()
        val service = GraphEditorStateService()

        service.asyncRequests.markGenerationPlan(
            GenerationPlan(
                source = com.charmnight.linkgraph.agent.model.GenerationPlanSource.REMOTE,
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
                promptPreview = "system: assistant history generation plan\nuser: inspect graph",
            ),
            requestState = AsyncRequestState.succeeded(
                requestId = 100,
                finishedAtEpochMillis = 1_000,
            ),
        )
        service.asyncRequests.markGeneratedCodeDrafts(
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "class:order-draft-dto",
                    title = "OrderDraftDto.java",
                    targetPath = "src/main/java/com/example/OrderDraftDto.java",
                    content = "package com.example;\npublic class OrderDraftDto {}",
                    warnings = listOf("草稿内警告需要保留"),
                ),
            ),
            warnings = listOf("全局草稿警告需要保留"),
            source = com.charmnight.linkgraph.agent.model.LlmResultSource.REMOTE,
            promptPreview = "system: assistant history code draft\nuser: produce DTO draft",
            requestState = AsyncRequestState.succeeded(
                requestId = 101,
                finishedAtEpochMillis = 1_001,
            ),
        )
        val current = service.snapshot()
        val generationPlanResultId = assertNotNull(
            current.assistantSessionState.turns.firstOrNull {
                it.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.GENERATION_PLAN
            }?.resultId,
        )
        val codeDraftResultId = assertNotNull(
            current.assistantSessionState.turns.firstOrNull {
                it.kind == com.charmnight.linkgraph.workbench.AssistantTurnKind.CODE_DRAFT
            }?.resultId,
        )
        val previous = current.copy(
            snapshotRevision = current.snapshotRevision - 1,
            assistantResultStore = com.charmnight.linkgraph.workbench.AssistantResultStore(),
        )

        val envelope = renderer.renderIncrementalEnvelopes(
            sessionId = "session-1",
            previousSnapshot = previous,
            snapshot = current,
        ).singleOrNull()
        assertNotNull(envelope)
        val envelopeState = assertNotNull(envelope.state as? BootstrapPayloadDto)

        val assistantResultStore = envelopeState.assistantResultStore
        assertNotNull(assistantResultStore)
        val generationPlanEntry = assertNotNull(assistantResultStore[generationPlanResultId] as? AssistantResultEntryDto)
        val historicalPlan = assertNotNull(generationPlanEntry.generationPlan)
        // P2-6: generationPlan 字段 promptPreview 被外化到 artifactStore，DTO 不再带这个字段
        // DTO 字段 promptPreviewArtifactId 必须非空
        assertTrue(historicalPlan.promptPreviewArtifactId?.isNotBlank() == true)

        val codeDraftEntry = assertNotNull(assistantResultStore[codeDraftResultId] as? AssistantResultEntryDto)
        assertEquals(listOf("全局草稿警告需要保留"), codeDraftEntry.codeDraftWarnings)
        val codeDrafts = assertNotNull(codeDraftEntry.codeDrafts)
        val historicalDraft = assertNotNull(codeDrafts.singleOrNull())
        // P2-6: content 外化到 artifact，DTO 的 content 字段为 null
        assertTrue(historicalDraft.content == null)
        assertTrue(historicalDraft.contentArtifactId?.isNotBlank() == true)

        val script = renderer.renderScript(listOf(envelope))
        assertFalse(script.contains("public class OrderDraftDto"))
        assertFalse(script.contains("system: assistant history generation plan"))
        assertFalse(script.contains("system: assistant history code draft"))
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
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:submit-order",
                    type = NodeType.METHOD,
                    title = "OrderController.submit",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        return com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
            semanticFactGraph = graph,
            workspaceBaseGraph = graph,
            workspaceGraph = graph,
            sceneStates = defaultGraphSceneStates().mapValues { (_, state) ->
                state.copy(layoutRevision = layoutRevision)
            },
            semanticRevision = semanticRevision,
            snapshotRevision = snapshotRevision,
            generatedCodeDrafts = generatedCodeDrafts,
        )
    }

    private fun GraphEditorTransportSliceRenderer.lastRenderedPayloadHashForTest(): String? {
        val field = GraphEditorTransportSliceRenderer::class.java.getDeclaredField("lastRenderedPayloadHash")
        field.isAccessible = true
        return field.get(this) as? String
    }
}
