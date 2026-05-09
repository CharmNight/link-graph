package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.capability.AgentCapability
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.artifact.GraphSummaryArtifact
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.model.GraphDocument
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    fun testPrunesRuntimeArtifactsFromPreviousRunsAfterCompletion() {
        val artifactStore = InMemoryArtifactStore()
        artifactStore.save(
            GraphSummaryArtifact(
                artifactId = "old-run-graph",
                graph = GraphDocument(),
                selectedNodeIds = emptyList(),
                graphSource = "previous",
            ),
        )
        artifactStore.save(
            PlanArtifact(
                artifactId = "plan-current",
                plan = GenerationPlan(
                    source = GenerationPlanSource.LOCAL_RULE,
                    summary = "当前 workbench 计划",
                ),
            ),
        )
        val coordinator = AgentRunCoordinator(
            stepExecutor = SavingArtifactStepExecutor(),
        )
        val runtimeContext = AgentRuntimeContext(
            project = project,
            snapshotSupplier = { null },
            artifactStore = artifactStore,
        )
        val capability = stringCapability(
            runId = "run-current",
            finalizeOutput = "ok",
        )

        val result = coordinator.run(
            capability = capability,
            input = "解释上传链路",
            runtimeContext = runtimeContext,
        )

        assertEquals("ok", result.output)
        assertEquals(null, artifactStore.get("old-run-graph"))
        assertNotNull(artifactStore.get("run-current-graph"))
        assertNotNull(artifactStore.get("plan-current"))
    }

    fun testPrunesRuntimeArtifactsFromPreviousRunsAfterFailure() {
        val artifactStore = InMemoryArtifactStore()
        artifactStore.save(
            GraphSummaryArtifact(
                artifactId = "old-run-graph",
                graph = GraphDocument(),
                selectedNodeIds = emptyList(),
                graphSource = "previous",
            ),
        )
        val coordinator = AgentRunCoordinator(
            stepExecutor = FailingStepExecutor(),
        )
        val runtimeContext = AgentRuntimeContext(
            project = project,
            snapshotSupplier = { null },
            artifactStore = artifactStore,
        )
        val capability = stringCapability(
            runId = "run-current",
            finalizeOutput = "unused",
        )

        val result = coordinator.run(
            capability = capability,
            input = "解释上传链路",
            runtimeContext = runtimeContext,
        )

        assertEquals(AgentRunPhase.FAILED, result.finalState.phase)
        assertEquals(null, artifactStore.get("old-run-graph"))
        assertNotNull(artifactStore.get("run-current-graph"))
    }

    fun testRuntimeContextCarriesDeadlineIntoStepExecution() {
        var observedRuntimeContext: AgentRuntimeContext? = null
        val coordinator = AgentRunCoordinator(
            stepExecutor = StepExecutor { state, runtimeContext ->
                observedRuntimeContext = runtimeContext
                AgentStepExecutionResult.fail(
                    state.copy(
                        phase = AgentRunPhase.FAILED,
                        failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                    ),
                )
            },
        )
        val runtimeContext = AgentRuntimeContext(
            project = project,
            snapshotSupplier = { null },
            artifactStore = InMemoryArtifactStore(),
        )

        coordinator.run(
            capability = stringCapability(
                runId = "run-deadline",
                finalizeOutput = "unused",
                budget = RunBudget(
                    maxRuntimeSeconds = 5,
                ),
            ),
            input = "解释上传链路",
            runtimeContext = runtimeContext,
        )

        val deadline = observedRuntimeContext?.deadlineEpochMillis ?: error("缺少 runtime deadline")
        assertFalse(observedRuntimeContext?.isDeadlineExceeded(nowEpochMillis = deadline - 1) ?: true)
        assertTrue(observedRuntimeContext?.isDeadlineExceeded(nowEpochMillis = deadline) ?: false)
    }

    private fun stringCapability(
        runId: String,
        finalizeOutput: String,
        budget: RunBudget = RunBudget(),
    ): AgentCapability<String, String> {
        return object : AgentCapability<String, String> {
            override val capabilityId: String = "qa"

            override fun buildInitialState(
                input: String,
                runtimeContext: AgentRuntimeContext,
            ): AgentRunState {
                return AgentRunState(
                    runId = runId,
                    capabilityId = capabilityId,
                    phase = AgentRunPhase.CREATED,
                    userGoal = input,
                    budget = budget,
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
                return finalizeOutput
            }
        }
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
                        summary = "delegate-service",
                    ),
                ),
            )
        }
    }

    private class SavingArtifactStepExecutor : StepExecutor {
        override fun executeNextStep(
            state: AgentRunState,
            runtimeContext: AgentRuntimeContext,
        ): AgentStepExecutionResult {
            val ref = runtimeContext.artifactStore.save(
                GraphSummaryArtifact(
                    artifactId = "${state.runId}-graph",
                    graph = GraphDocument(),
                    selectedNodeIds = emptyList(),
                    graphSource = "current",
                ),
            )
            return AgentStepExecutionResult.complete(
                state.copy(
                    phase = AgentRunPhase.SUCCEEDED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + ref,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.SUCCEEDED,
                        summary = "save-current-artifact",
                    ),
                    lastModelOutput = "ok",
                ),
            )
        }
    }

    private class FailingStepExecutor : StepExecutor {
        override fun executeNextStep(
            state: AgentRunState,
            runtimeContext: AgentRuntimeContext,
        ): AgentStepExecutionResult {
            val ref = runtimeContext.artifactStore.save(
                GraphSummaryArtifact(
                    artifactId = "${state.runId}-graph",
                    graph = GraphDocument(),
                    selectedNodeIds = emptyList(),
                    graphSource = "current",
                ),
            )
            return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + ref,
                    failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "save-current-artifact-then-fail",
                    ),
                    lastModelOutput = "failed",
                ),
            )
        }
    }
}
