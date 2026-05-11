package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.usecase.GenerationUseCase
import com.charmnight.linkgraph.application.usecase.GenerationUseCaseResult
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenerationUseCaseTest {
    @Test
    fun resolvesMissingRuntimePlanWithFallbackWarning() {
        val result = useCase().resolvePlan(
            payload = PlanningInput(GraphDocument(), GraphDiff(), emptyList<SyncPreviewItem>()),
            runtimeResult = AgentRunResult(finalState = runState(), output = null),
            requestState = AsyncRequestState.succeeded(scene = "实现计划", fallbackUsed = true),
            runtimeArtifacts = emptyList(),
        )

        val ready = assertIs<GenerationUseCaseResult.PlanReady>(result)
        assertEquals(ApplicationFeedbackLevel.WARNING, ready.presentation.feedbackLevel)
        assertTrue(ready.presentation.plan.warnings.single().contains("runtime 未返回结果"))
    }

    @Test
    fun mapsEmptyCodeGenerationResultToFailurePresentation() {
        val result = useCase().resolveCodeDrafts(
            runtimeResult = AgentRunResult(
                finalState = runState(),
                output = CodeGenerationResult(
                    drafts = emptyList(),
                    warnings = listOf("没有可写入文件"),
                    source = LlmResultSource.LOCAL_RULE,
                ),
            ),
            requestState = AsyncRequestState.running(scene = "代码草稿"),
            runtimeArtifacts = emptyList(),
        )

        val failed = assertIs<GenerationUseCaseResult.CodeDraftFailed>(result)
        assertEquals("代码草稿", failed.presentation.scene)
        assertTrue(failed.presentation.message.contains("没有可写入文件"))
    }

    @Test
    fun mapsPreparedDraftsToReadyResult() {
        val prepared = listOf(
            GeneratedCodeDraft(
                id = "draft-1",
                sourceNodeId = "node-1",
                title = "Draft",
                targetPath = "src/main/kotlin/Draft.kt",
                content = "class Draft",
            ),
        )

        val result = useCase().resolveCodeDrafts(
            runtimeResult = AgentRunResult(
                finalState = runState(),
                output = CodeGenerationResult(
                    drafts = listOf(prepared.single().copy(content = "raw")),
                    source = LlmResultSource.REMOTE,
                    promptPreview = "prompt",
                ),
            ),
            requestState = AsyncRequestState.succeeded(scene = "代码草稿"),
            runtimeArtifacts = emptyList(),
            preparedDrafts = prepared,
        )

        val ready = assertIs<GenerationUseCaseResult.CodeDraftsReady>(result)
        assertEquals(prepared, ready.presentation.drafts)
        assertEquals(true, ready.presentation.requestState.promptPreviewAvailable)
    }

    private fun useCase(): GenerationUseCase {
        return GenerationUseCase(
            planSnapshotBuilder = { _, _, _, _, _, _ ->
                GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "fallback",
                    promptPreview = "",
                )
            },
            projectBasePathProvider = { null },
        )
    }

    private fun runState(
        failureReason: AgentRunFailureReason? = null,
    ): AgentRunState {
        return AgentRunState(
            runId = "run",
            capabilityId = "capability",
            phase = if (failureReason == null) AgentRunPhase.SUCCEEDED else AgentRunPhase.FAILED,
            userGoal = "test",
            budget = RunBudget(),
            stepIndex = 0,
            artifactRefs = emptyList(),
            failureReason = failureReason,
        )
    }
}
