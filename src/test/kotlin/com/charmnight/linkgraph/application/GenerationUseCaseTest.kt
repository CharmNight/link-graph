package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
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

    /**
     * P3-4：runtime 自身失败（output==null + finalState.failureReason 非空）时，
     * resolveCodeDrafts 必须走 resolveCodegenRuntimeFailure 路径产出 CodeDraftFailed，
     * 而不是 NPE 或返回空 Ready。
     */
    @Test
    fun mapsRuntimeFailureWithNoOutputToFailurePresentation() {
        val result = useCase().resolveCodeDrafts(
            runtimeResult = AgentRunResult(
                finalState = runState(failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT),
                output = null,
            ),
            requestState = AsyncRequestState.running(scene = "代码草稿"),
            runtimeArtifacts = emptyList(),
        )

        val failed = assertIs<GenerationUseCaseResult.CodeDraftFailed>(result)
        assertTrue(
            failed.presentation.message.contains("证据不足以形成安全代码"),
            "EVIDENCE_INSUFFICIENT 应映射到证据不足提示；实际：${failed.presentation.message}",
        )
    }

    /**
     * P3-4：runtime 在本地安全校验阶段失败（stepRecords.last.summary = "validate-generated-drafts"）
     * 必须把消息映射到"未通过本地安全校验"，覆盖 step-summary 分支。
     */
    @Test
    fun mapsRuntimeSafetyValidationRejectionToFailurePresentation() {
        val stateWithValidationFailure = runState().copy(
            stepIndex = 2,
            // 模拟最后一步是 validate-generated-drafts 的 runtime 状态
            stepRecords = listOf(
                com.charmnight.linkgraph.llm.runtime.AgentStepRecord(
                    stepIndex = 0,
                    phase = AgentRunPhase.RUNNING,
                    summary = "plan",
                ),
                com.charmnight.linkgraph.llm.runtime.AgentStepRecord(
                    stepIndex = 1,
                    phase = AgentRunPhase.RUNNING,
                    summary = "generate",
                ),
                com.charmnight.linkgraph.llm.runtime.AgentStepRecord(
                    stepIndex = 2,
                    phase = AgentRunPhase.RUNNING,
                    summary = "validate-generated-drafts",
                ),
            ),
        )
        val result = useCase().resolveCodeDrafts(
            runtimeResult = AgentRunResult(
                finalState = stateWithValidationFailure,
                output = null,
            ),
            requestState = AsyncRequestState.running(scene = "代码草稿"),
            runtimeArtifacts = emptyList(),
        )

        val failed = assertIs<GenerationUseCaseResult.CodeDraftFailed>(result)
        assertTrue(
            failed.presentation.message.contains("未通过本地安全校验"),
            "stepSummary=validate-generated-drafts 应映射到本地安全校验失败；实际：${failed.presentation.message}",
        )
    }

    private fun useCase(): GenerationUseCase {
        return GenerationUseCase(
            planSnapshotBuilder = { _, _, _, _, _, _, _ ->
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
