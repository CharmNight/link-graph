package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.usecase.ReviewUseCase
import com.charmnight.linkgraph.application.usecase.ReviewUseCaseResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.QaRequestKind
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReviewUseCaseTest {
    @Test
    fun mapsMissingRuntimeOutputToFailedResultAndFallbackPatch() {
        val context = modeContext()

        val result = ReviewUseCase { output, _ -> output }.resolveQaRuntimeResult(
            runtimeResult = AgentRunResult(
                finalState = runState(
                    failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                    lastModelOutput = "当前工作台没有可供问答的图节点，无法继续执行。",
                ),
                output = null,
            ),
            modeContext = context,
            requestState = AsyncRequestState.running(scene = "问答"),
            runtimeArtifacts = emptyList(),
            draftValidationState = null,
            codeEligibilityDecision = null,
        )

        val failed = assertIs<ReviewUseCaseResult.QaFailed>(result)
        assertEquals(
            "问答失败：runtime 未返回结果（EVIDENCE_INSUFFICIENT：当前工作台没有可供问答的图节点，无法继续执行。）。",
            failed.presentation.message,
        )
        assertEquals(failed.presentation.message, failed.fallbackResult.answer)
        assertEquals(context.question, failed.fallbackResult.question)
        assertTrue(failed.fallbackResult.warnings.single().contains("EVIDENCE_INSUFFICIENT"))
    }

    @Test
    fun mapsRuntimeOutputToCompletedResult() {
        val output = GraphPatchResult(
            source = LlmResultSource.REMOTE,
            question = "解释风险",
            answer = "answer",
            promptPreview = "prompt",
        )
        val result = ReviewUseCase { patch, _ -> patch.copy(answer = patch.answer + " normalized") }
            .resolveQaRuntimeResult(
                runtimeResult = AgentRunResult(finalState = runState(), output = output),
                modeContext = modeContext(),
                requestState = AsyncRequestState.succeeded(scene = "问答"),
                runtimeArtifacts = emptyList(),
                draftValidationState = null,
                codeEligibilityDecision = null,
            )

        val completed = assertIs<ReviewUseCaseResult.QaCompleted>(result)
        assertEquals("answer normalized", completed.presentation.result.answer)
        assertEquals(true, completed.presentation.requestState.promptPreviewAvailable)
    }

    private fun modeContext(): QaModeContext {
        return QaModeContext(
            request = ReplayableQaRequest(
                requestId = "qa-1",
                kind = QaRequestKind.ASK,
                question = "解释风险",
                mode = QaMode.AUTO,
            ),
            effectiveMode = QaMode.ANSWER,
        )
    }

    private fun runState(
        failureReason: AgentRunFailureReason? = null,
        lastModelOutput: String? = null,
    ): AgentRunState {
        return AgentRunState(
            runId = "run",
            capabilityId = "qa",
            phase = if (failureReason == null) AgentRunPhase.SUCCEEDED else AgentRunPhase.FAILED,
            userGoal = "test",
            budget = RunBudget(),
            stepIndex = 0,
            artifactRefs = emptyList(),
            failureReason = failureReason,
            lastModelOutput = lastModelOutput,
        )
    }
}
