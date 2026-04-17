package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.capability.AgentCapability
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentRunCoordinatorTest : BasePlatformTestCase() {
    fun testRunsSingleStepCapabilityAndReturnsFinalOutput() {
        val coordinator = AgentRunCoordinator(
            stepExecutor = CompletingStepExecutor("问答结论"),
        )
        val runtimeContext = AgentRuntimeContext(
            project = project,
            snapshotSupplier = { null },
            artifactStore = InMemoryArtifactStore(),
        )
        val capability = object : AgentCapability<String, String> {
            override val capabilityId: String = "qa"

            override fun buildInitialState(
                input: String,
                runtimeContext: AgentRuntimeContext,
            ): AgentRunState {
                return AgentRunState(
                    runId = "run-fake-qa",
                    capabilityId = capabilityId,
                    phase = AgentRunPhase.CREATED,
                    userGoal = input,
                    budget = RunBudget(),
                    stepIndex = 0,
                    artifactRefs = emptyList(),
                )
            }

            override fun allowedTools(input: String): Set<String> = emptySet()

            override fun stopPolicy(input: String): StopPolicy = StopPolicy.default()

            override fun finalize(
                runState: AgentRunState,
                runtimeContext: AgentRuntimeContext,
            ): String {
                return runState.lastModelOutput ?: error("缺少最终输出")
            }
        }

        val result = coordinator.run(
            capability = capability,
            input = "解释上传链路",
            runtimeContext = runtimeContext,
        )

        assertEquals("run-fake-qa", result.finalState.runId)
        assertEquals(AgentRunPhase.SUCCEEDED, result.finalState.phase)
        assertEquals("问答结论", result.output)
        assertEquals(1, result.finalState.stepIndex)
        assertEquals(1, result.finalState.stepRecords.size)
    }

    fun testReturnsExplicitFailureReasonWhenStopPolicyRejectsTheRun() {
        val coordinator = AgentRunCoordinator(
            stepExecutor = CompletingStepExecutor("不会被采用"),
        )
        val runtimeContext = AgentRuntimeContext(
            project = project,
            snapshotSupplier = { null },
            artifactStore = InMemoryArtifactStore(),
        )
        val capability = object : AgentCapability<String, String> {
            override val capabilityId: String = "qa"

            override fun buildInitialState(
                input: String,
                runtimeContext: AgentRuntimeContext,
            ): AgentRunState {
                return AgentRunState(
                    runId = "run-budget-failed",
                    capabilityId = capabilityId,
                    phase = AgentRunPhase.RUNNING,
                    userGoal = input,
                    budget = RunBudget(maxSteps = 0).recordStep(),
                    stepIndex = 1,
                    artifactRefs = emptyList(),
                )
            }

            override fun allowedTools(input: String): Set<String> = emptySet()

            override fun stopPolicy(input: String): StopPolicy = StopPolicy.default()

            override fun finalize(
                runState: AgentRunState,
                runtimeContext: AgentRuntimeContext,
            ): String {
                return runState.lastModelOutput ?: ""
            }
        }

        val result = coordinator.run(
            capability = capability,
            input = "解释上传链路",
            runtimeContext = runtimeContext,
        )

        assertEquals(AgentRunPhase.FAILED, result.finalState.phase)
        assertEquals(AgentRunFailureReason.MAX_STEPS_EXCEEDED, result.finalState.failureReason)
        assertEquals(null, result.output)
        assertNotNull(result.finalState.failureReason)
    }

    private class CompletingStepExecutor(
        private val finalOutput: String,
    ) : StepExecutor {
        override fun executeNextStep(
            state: AgentRunState,
            runtimeContext: AgentRuntimeContext,
        ): AgentStepExecutionResult {
            return AgentStepExecutionResult.complete(
                state.copy(
                    phase = AgentRunPhase.SUCCEEDED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    lastModelOutput = finalOutput,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.SUCCEEDED,
                        summary = "delegate-legacy-service",
                    ),
                ),
            )
        }
    }
}
