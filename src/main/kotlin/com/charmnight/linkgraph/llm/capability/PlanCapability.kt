package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.GraphDiffArtifact
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.AgentStepExecutionResult
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.runtime.StepExecutor
import com.charmnight.linkgraph.llm.runtime.StopPolicy
import com.charmnight.linkgraph.llm.runtime.budgetExceededStepResult
import com.charmnight.linkgraph.llm.runtime.failureReasonBeforeNextFileRead
import com.charmnight.linkgraph.llm.runtime.failureReasonForBudget
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.CodeReadToolFacade
import com.charmnight.linkgraph.llm.tools.DraftToolFacade
import com.charmnight.linkgraph.llm.tools.GetConfirmedIntentTool
import com.charmnight.linkgraph.llm.tools.GetDraftWorkbenchTool
import com.charmnight.linkgraph.llm.tools.GetGraphDiffTool
import com.charmnight.linkgraph.llm.tools.GraphToolFacade
import com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.services.PlanningPayload
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import java.util.UUID

/**
 * 计划生成 capability。
 * 第一阶段仍复用旧 GraphGenerationService 路径，但执行入口和 confirmed intent 门槛已经迁到 runtime。
 */
internal class PlanCapability(
    private val defaultBudget: RunBudget = RunBudget(),
    private val legacyPlanExecutor: LegacyPlanExecutor,
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        listOf(
            GetDraftWorkbenchTool(DraftToolFacade()),
            GetConfirmedIntentTool(DraftToolFacade()),
            GetGraphDiffTool(GraphToolFacade()),
            ReadSourceSnippetTool(CodeReadToolFacade()),
        ),
    ),
) : AgentCapability<PlanCapabilityInput, GenerationPlan> {
    override val capabilityId: String = "plan"

    override fun buildInitialState(input: PlanCapabilityInput, runtimeContext: AgentRuntimeContext): AgentRunState {
        return AgentRunState(
            runId = "plan-${UUID.randomUUID()}",
            capabilityId = capabilityId,
            phase = AgentRunPhase.CREATED,
            userGoal = "生成实现计划",
            budget = defaultBudget,
            stepIndex = 0,
            artifactRefs = emptyList(),
        )
    }

    override fun allowedTools(input: PlanCapabilityInput): Set<String> {
        return setOf("get_draft_workbench", "get_confirmed_intent", "get_graph_diff", "read_source_snippet")
    }

    override fun stopPolicy(input: PlanCapabilityInput): StopPolicy = StopPolicy.default()

    override fun finalize(runState: AgentRunState, runtimeContext: AgentRuntimeContext): GenerationPlan {
        val artifact = runState.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<PlanArtifact>()
            .lastOrNull()
            ?: error("计划运行结束时缺少 PlanArtifact")
        return artifact.plan
    }

    override fun createStepExecutor(input: PlanCapabilityInput): StepExecutor {
        return StepExecutor { state, runtimeContext ->
            when (state.stepIndex) {
                0 -> readDraftWorkbench(state, runtimeContext)
                1 -> readConfirmedIntent(state, runtimeContext)
                2 -> readGraphDiff(state, runtimeContext)
                3 -> readCodeEvidence(state, runtimeContext)
                else -> generatePlan(state, runtimeContext, input)
            }
        }
    }

    private fun readDraftWorkbench(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取草稿边界。",
            ),
        )
        val result = toolRegistry.require("get_draft_workbench").invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
                runBudget = state.budget,
            ),
        )
        val candidateCount = result.payload["candidateCount"] as? Int ?: 0
        val confirmedCount = result.payload["confirmedCount"] as? Int ?: 0
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "read-draft-workbench",
                    toolName = result.toolName,
                ),
                lastModelOutput = "已读取草稿边界：candidate=$candidateCount, confirmed=$confirmedCount。",
            ),
        )
    }

    private fun readConfirmedIntent(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取 confirmed intent。",
            ),
        )
        val result = toolRegistry.require("get_confirmed_intent").invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
                runBudget = state.budget,
            ),
        )
        val confirmedCount = result.payload["confirmedCount"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val artifactRefs = result.payload["artifactRefs"] as? List<com.charmnight.linkgraph.llm.artifact.ArtifactRef> ?: emptyList()
        if (confirmedCount <= 0) {
            return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.FAILED,
                        summary = "read-confirmed-intent",
                        toolName = result.toolName,
                    ),
                    lastModelOutput = "当前没有已确认正式意图，禁止生成计划。",
                ),
            )
        }
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                artifactRefs = state.artifactRefs + artifactRefs,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                        summary = "read-confirmed-intent",
                        toolName = result.toolName,
                    ),
                lastModelOutput = "已读取 confirmed intent，准备生成计划。",
            ),
        )
    }

    private fun readGraphDiff(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取图差异。",
            ),
        )
        val result = toolRegistry.require("get_graph_diff").invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = runtimeContext.project,
                snapshot = snapshot,
                artifactStore = runtimeContext.artifactStore,
                runBudget = state.budget,
            ),
        )
        val diff = result.payload["diff"] as? com.charmnight.linkgraph.model.GraphDiff
            ?: return AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                    lastModelOutput = "当前没有可用图差异，无法生成实现计划。",
                ),
            )
        val artifactRef = runtimeContext.artifactStore.save(
            GraphDiffArtifact(
                artifactId = "${state.runId}-graph-diff-${state.stepIndex}",
                diff = diff,
            ),
        )
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                artifactRefs = state.artifactRefs + artifactRef,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = "read-graph-diff",
                    toolName = result.toolName,
                ),
                lastModelOutput = "已读取图差异，准备补充计划证据。",
            ),
        )
    }

    private fun readCodeEvidence(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult {
        val snapshot = runtimeContext.snapshotSupplier() ?: return AgentStepExecutionResult.fail(
            state.copy(
                phase = AgentRunPhase.FAILED,
                budget = state.budget.recordStep(),
                stepIndex = state.stepIndex + 1,
                failureReason = AgentRunFailureReason.EVIDENCE_INSUFFICIENT,
                lastModelOutput = "缺少编辑器快照，无法读取计划证据。",
            ),
        )
        val confirmedChanges = extractConfirmedIntents(state, runtimeContext)
        var nextBudget = state.budget.recordStep()
        val nextArtifacts = state.artifactRefs.toMutableList()
        var usedTool = false
        confirmedChanges
            .flatMap { change -> change.editScopes }
            .distinctBy { scope -> "${scope.filePath}:${scope.startLine}:${scope.endLine}" }
            .forEachIndexed { index, scope ->
                nextBudget.failureReasonBeforeNextFileRead()?.let { reason ->
                    return budgetExceededStepResult(
                        state = state,
                        budget = nextBudget,
                        failureReason = reason,
                            summary = "read-plan-code-evidence",
                            toolName = if (usedTool) "read_source_snippet" else null,
                            nodeId = scope.targetNodeId,
                            artifactRefs = nextArtifacts,
                            lastModelOutput = "runtime 预算已耗尽，停止继续读取计划代码证据。",
                        )
                }
                val result = toolRegistry.require("read_source_snippet").invoke(
                    input = mapOf(
                        "filePath" to scope.filePath,
                        "startLine" to scope.startLine,
                        "endLine" to scope.endLine,
                    ),
                    context = ToolExecutionContext(
                        project = runtimeContext.project,
                        snapshot = snapshot,
                        artifactStore = runtimeContext.artifactStore,
                        runBudget = nextBudget,
                    ),
                )
                val snippet = result.payload["snippet"]?.toString().orEmpty()
                if (snippet.isNotBlank()) {
                    usedTool = true
                    val ref = runtimeContext.artifactStore.save(
                        CodeEvidenceArtifact(
                            artifactId = "${state.runId}-plan-code-evidence-$index",
                            nodeId = scope.targetNodeId,
                            filePath = scope.filePath,
                            snippet = snippet,
                            startLine = scope.startLine,
                            endLine = scope.endLine,
                        ),
                    )
                    nextArtifacts += ref
                    nextBudget = nextBudget.recordFileRead(snippet.lineSequence().count())
                    StopPolicy.default().failureReasonForBudget(state, nextBudget)?.let { reason ->
                        return budgetExceededStepResult(
                            state = state,
                            budget = nextBudget,
                            failureReason = reason,
                            summary = "read-plan-code-evidence",
                            toolName = "read_source_snippet",
                            nodeId = scope.targetNodeId,
                            artifactRefs = nextArtifacts,
                            lastModelOutput = "读取计划代码证据后触发 runtime 预算上限。",
                        )
                    }
                }
            }
        return AgentStepExecutionResult.continueWith(
            state.copy(
                phase = AgentRunPhase.RUNNING,
                budget = nextBudget,
                stepIndex = state.stepIndex + 1,
                artifactRefs = nextArtifacts,
                stepRecords = state.stepRecords + AgentStepRecord(
                    stepIndex = state.stepIndex,
                    phase = AgentRunPhase.RUNNING,
                    summary = if (usedTool) "read-plan-code-evidence" else "skip-plan-code-evidence",
                    toolName = if (usedTool) "read_source_snippet" else null,
                    nodeId = confirmedChanges.flatMap { change -> change.editScopes }.firstOrNull()?.targetNodeId,
                ),
                lastModelOutput = if (usedTool) {
                    "已读取计划所需代码证据，准备生成实现计划。"
                } else {
                    "当前 confirmed intent 没有可读取的 edit scope，直接用已确认意图生成实现计划。"
                },
            ),
        )
    }

    private fun generatePlan(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: PlanCapabilityInput,
    ): AgentStepExecutionResult {
        return runCatching {
            val confirmedChanges = extractConfirmedIntents(state, runtimeContext)
            val runtimeDiff = state.artifactRefs
                .asSequence()
                .mapNotNull(runtimeContext.artifactStore::get)
                .filterIsInstance<GraphDiffArtifact>()
                .lastOrNull()
                ?.diff
                ?: input.planningPayload.diff
            val runtimeSourceContext = state.artifactRefs
                .asSequence()
                .mapNotNull(runtimeContext.artifactStore::get)
                .filterIsInstance<CodeEvidenceArtifact>()
                .map { artifact ->
                    val matchingScope = confirmedChanges
                        .asSequence()
                        .flatMap { change -> change.editScopes.asSequence() }
                        .firstOrNull { scope ->
                            scope.filePath == artifact.filePath &&
                                scope.startLine == artifact.startLine &&
                                scope.endLine == artifact.endLine
                        }
                    SourceSnippetContext(
                        nodeId = matchingScope?.targetNodeId.orEmpty(),
                        filePath = artifact.filePath,
                        startLine = artifact.startLine,
                        endLine = artifact.endLine,
                        snippet = artifact.snippet,
                    )
                }
                .toList()
            val runtimeSnapshot = input.planningPayload.snapshot.copy(
                draftWorkbenchState = DraftWorkbenchState(
                    draftChanges = confirmedChanges,
                ),
            )
            val plan = legacyPlanExecutor.invoke(
                input.copy(
                    planningPayload = input.planningPayload.copy(
                        snapshot = runtimeSnapshot,
                        diff = runtimeDiff,
                        sourceContext = runtimeSourceContext,
                    ),
                ),
                runtimeContext,
                state,
            )
            val artifactRef = runtimeContext.artifactStore.save(
                PlanArtifact(
                    artifactId = "plan-current",
                    plan = plan,
                ),
            )
            AgentStepExecutionResult.complete(
                state.copy(
                    phase = AgentRunPhase.SUCCEEDED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + artifactRef,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.SUCCEEDED,
                        summary = "delegate-legacy-plan-service",
                    ),
                    lastModelOutput = plan.summary,
                ),
            )
        }.getOrElse { throwable ->
            AgentStepExecutionResult.fail(
                state.copy(
                    phase = AgentRunPhase.FAILED,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    failureReason = AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                    lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                ),
            )
        }
    }

    private fun extractConfirmedIntents(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): List<DraftWorkbenchEntry> {
        return state.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<ConfirmedIntentArtifact>()
            .map(ConfirmedIntentArtifact::entry)
            .toList()
    }

    fun interface LegacyPlanExecutor {
        fun invoke(
            input: PlanCapabilityInput,
            runtimeContext: AgentRuntimeContext,
            state: AgentRunState,
        ): GenerationPlan
    }
}

internal data class PlanCapabilityInput(
    /** 已准备好的规划载荷。 */
    val planningPayload: PlanningPayload,
)
