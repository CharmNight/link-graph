package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.port.CodeDraftWritePresentation
import com.charmnight.linkgraph.application.port.GeneratedCodeDraftsPresentation
import com.charmnight.linkgraph.application.port.GenerationPlanPresentation
import com.charmnight.linkgraph.application.port.GenerationRequestScene
import com.charmnight.linkgraph.application.port.GenerationRequestStartedPresentation
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import kotlin.test.Test
import kotlin.test.assertEquals

class GenerationStatePresenterTest {
    @Test
    fun mapsGeneratedPlanResultToAsyncStateArtifactsAndFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = GenerationStatePresenter(stateService)
        val plan = GenerationPlan(
            source = GenerationPlanSource.LOCAL_RULE,
            summary = "plan",
        )

        presenter.presentGenerationPlan(
            GenerationPlanPresentation(
                plan = plan,
                requestState = AsyncRequestState.succeeded(scene = "实现计划", statusMessage = "实现计划已生成。"),
                runtimeArtifacts = listOf(ApplicationRuntimeArtifactSummary("artifact-1", "plan", "Plan")),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "实现计划已生成。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(plan, snapshot.generationPlan)
        assertEquals("实现计划已生成。", snapshot.generationPlanRequestState.statusMessage)
        assertEquals("Plan", snapshot.runtimeArtifactSummaries["plan"]?.single()?.title)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsCodeDraftWriteReportToWorkbenchStateAndOptionalFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = GenerationStatePresenter(stateService)

        presenter.presentCodeDraftWriteReport(
            CodeDraftWritePresentation(
                report = GeneratedCodeDraftWriteReport(writtenFiles = listOf("src/App.kt")),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "代码草稿已写入当前文件。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(listOf("src/App.kt"), snapshot.generatedCodeDraftWriteReport?.writtenFiles)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
        assertEquals("代码草稿已写入当前文件。", snapshot.operationFeedback?.message)
    }

    @Test
    fun mapsGeneratedCodeDraftsToAsyncStateArtifactsAndFeedback() {
        val stateService = GraphEditorStateService()
        val presenter = GenerationStatePresenter(stateService)

        presenter.presentGeneratedCodeDrafts(
            GeneratedCodeDraftsPresentation(
                drafts = listOf(
                    GeneratedCodeDraft(
                        id = "draft-1",
                        sourceNodeId = "node-1",
                        targetPath = "src/App.kt",
                        title = "App",
                    ),
                ),
                warnings = listOf("warn"),
                source = LlmResultSource.LOCAL_RULE,
                promptPreview = "prompt",
                requestState = AsyncRequestState.succeeded(scene = "代码草稿", statusMessage = "代码草稿已生成。"),
                runtimeArtifacts = listOf(ApplicationRuntimeArtifactSummary("artifact-1", "codegen", "Codegen")),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = "代码草稿已生成。",
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals("draft-1", snapshot.generatedCodeDrafts.single().id)
        assertEquals(listOf("warn"), snapshot.generatedCodeDraftWarnings)
        assertEquals("Codegen", snapshot.runtimeArtifactSummaries["codegen"]?.single()?.title)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }

    @Test
    fun mapsStreamingPreviewThroughGenerationRequestScene() {
        val stateService = GraphEditorStateService()
        var browserSyncCount = 0
        val presenter = GenerationStatePresenter(stateService) {
            browserSyncCount += 1
        }

        presenter.presentRequestStarted(
            GenerationRequestStartedPresentation(
                scene = GenerationRequestScene.PLAN,
                requestState = AsyncRequestState.running(requestId = 42L, scene = "实现计划", streaming = true),
                feedbackMessage = "正在生成实现计划。",
            ),
        )

        presenter.presentStreamingPreview(
            scene = GenerationRequestScene.PLAN,
            requestId = 42L,
            previewText = "partial plan",
            finalizingStructuredResult = true,
        )

        val snapshot = stateService.snapshot()
        assertEquals("partial plan", snapshot.generationPlanRequestState.previewText)
        assertEquals("FINALIZING", snapshot.generationPlanRequestState.streamPhase)
        assertEquals(true, snapshot.generationPlanRequestState.finalizingStructuredResult)
        assertEquals(2, browserSyncCount)
    }
}
