package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals

class StopPolicyTest {
    @Test
    fun stopsWhenMaxStepsIsExceeded() {
        val state = runningState(
            RunBudget(maxSteps = 1).recordStep().recordStep(),
        )

        val reason = StopPolicy.default().evaluate(state, nowEpochMillis = 5_000)

        assertEquals(AgentRunFailureReason.MAX_STEPS_EXCEEDED, reason)
    }

    @Test
    fun stopsWhenMaxFilesReadIsExceeded() {
        val state = runningState(
            RunBudget(maxFilesRead = 1).recordFileRead(snippetLines = 12).recordFileRead(snippetLines = 18),
        )

        val reason = StopPolicy.default().evaluate(state, nowEpochMillis = 5_000)

        assertEquals(AgentRunFailureReason.MAX_FILES_READ_EXCEEDED, reason)
    }

    @Test
    fun stopsWhenMaxSnippetsIsExceeded() {
        val state = runningState(
            RunBudget(maxSnippets = 1).recordFileRead(snippetLines = 12).recordFileRead(snippetLines = 18),
        )

        val reason = StopPolicy.default().evaluate(state, nowEpochMillis = 5_000)

        assertEquals(AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED, reason)
    }

    @Test
    fun stopsWhenSingleSnippetExceedsMaxSnippetLines() {
        val state = runningState(
            RunBudget(maxSnippetLines = 10).recordFileRead(snippetLines = 11),
        )

        val reason = StopPolicy.default().evaluate(state, nowEpochMillis = 5_000)

        assertEquals(AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED, reason)
    }

    @Test
    fun stopsWhenMaxTotalSnippetLinesIsExceeded() {
        val state = runningState(
            RunBudget(maxTotalSnippetLines = 50)
                .recordFileRead(snippetLines = 30)
                .recordFileRead(snippetLines = 25),
        )

        val reason = StopPolicy.default().evaluate(state, nowEpochMillis = 5_000)

        assertEquals(AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED, reason)
    }

    private fun runningState(budget: RunBudget): AgentRunState {
        return AgentRunState(
            runId = "run-budget-check",
            capabilityId = "qa",
            phase = AgentRunPhase.RUNNING,
            userGoal = "验证预算停止条件",
            budget = budget,
            stepIndex = budget.usedSteps,
            artifactRefs = emptyList(),
        )
    }
}
