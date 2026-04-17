package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.artifact.ArtifactRef
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRunStateTest {
    @Test
    fun stateCarriesRunIdentityBudgetArtifactsAndFailureReason() {
        val budget = RunBudget(
            maxSteps = 10,
            maxFilesRead = 15,
            maxSnippets = 30,
            maxSnippetLines = 120,
            maxTotalSnippetLines = 1_200,
            maxRuntimeSeconds = 90,
        )
        val state = AgentRunState(
            runId = "run-qa-001",
            capabilityId = "qa",
            phase = AgentRunPhase.RUNNING,
            userGoal = "解释上传链路的真实行为",
            budget = budget,
            stepIndex = 1,
            artifactRefs = listOf(ArtifactRef("artifact-graph-summary")),
            lastModelOutput = "需要继续读取代码证据",
            failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
        )

        assertEquals("run-qa-001", state.runId)
        assertEquals("qa", state.capabilityId)
        assertEquals(AgentRunPhase.RUNNING, state.phase)
        assertEquals("解释上传链路的真实行为", state.userGoal)
        assertEquals(10, state.budget.maxSteps)
        assertEquals(1, state.stepIndex)
        assertEquals("artifact-graph-summary", state.artifactRefs.single().artifactId)
        assertEquals("需要继续读取代码证据", state.lastModelOutput)
        assertEquals(AgentRunFailureReason.EVIDENCE_INSUFFICIENT, state.failureReason)
    }
}
