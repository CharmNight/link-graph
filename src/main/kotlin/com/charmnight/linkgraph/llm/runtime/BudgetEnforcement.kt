package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.artifact.ArtifactRef

internal fun RunBudget.failureReasonBeforeNextFileRead(): AgentRunFailureReason? {
    return when {
        filesRead >= maxFilesRead -> AgentRunFailureReason.MAX_FILES_READ_EXCEEDED
        snippetsRead >= maxSnippets -> AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED
        totalSnippetLinesRead >= maxTotalSnippetLines -> AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED
        else -> null
    }
}

internal fun StopPolicy.failureReasonForBudget(
    state: AgentRunState,
    budget: RunBudget,
): AgentRunFailureReason? {
    return evaluate(state.copy(budget = budget))
}

internal fun budgetExceededStepResult(
    state: AgentRunState,
    budget: RunBudget,
    failureReason: AgentRunFailureReason,
    summary: String,
    lastModelOutput: String,
    artifactRefs: List<ArtifactRef> = state.artifactRefs,
    toolName: String? = null,
    nodeId: String? = null,
): AgentStepExecutionResult.Fail {
    return AgentStepExecutionResult.Fail(
        state.copy(
            phase = AgentRunPhase.FAILED,
            budget = budget,
            stepIndex = state.stepIndex + 1,
            artifactRefs = artifactRefs,
            stepRecords = state.stepRecords + AgentStepRecord(
                stepIndex = state.stepIndex,
                phase = AgentRunPhase.FAILED,
                summary = summary,
                toolName = toolName,
                nodeId = nodeId,
            ),
            failureReason = failureReason,
            lastModelOutput = lastModelOutput,
        ),
    )
}
