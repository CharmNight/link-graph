package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.CodeEvidenceArtifact
import com.charmnight.linkgraph.llm.artifact.ConfirmedIntentArtifact
import com.charmnight.linkgraph.llm.artifact.GraphDiffArtifact
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason
import com.charmnight.linkgraph.llm.runtime.AgentRunPhase
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeDeadlineExceededException
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.AgentStepExecutionResult
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.llm.runtime.StepExecutor
import com.charmnight.linkgraph.llm.runtime.StopPolicy
import com.charmnight.linkgraph.llm.runtime.budgetExceededStepResult
import com.charmnight.linkgraph.llm.runtime.failureReasonBeforeNextFileRead
import com.charmnight.linkgraph.llm.runtime.failureReasonForBudget
import com.charmnight.linkgraph.llm.runtime.withConfiguredRuntimeTimeout
import com.charmnight.linkgraph.llm.tools.AgentToolRegistry
import com.charmnight.linkgraph.llm.tools.CodeReadToolFacade
import com.charmnight.linkgraph.llm.tools.DraftToolFacade
import com.charmnight.linkgraph.llm.tools.GetConfirmedIntentTool
import com.charmnight.linkgraph.llm.tools.GetDraftWorkbenchTool
import com.charmnight.linkgraph.llm.tools.GetGraphDiffTool
import com.charmnight.linkgraph.llm.tools.GraphToolFacade
import com.charmnight.linkgraph.llm.tools.ReadSourceSnippetTool
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import java.util.UUID

/**
 * 计划生成 capability。
 * runtime 负责门槛校验与证据读取，最终计划由正式执行器生成。
 */
internal class PlanCapability(
    /** 默认运行预算，控制运行期间的最大步数与文件读取上限。 */
    private val defaultBudget: RunBudget = RunBudget(),
    /** 真正生成实现计划的执行器，通常由上层注入远程模型调用实现。 */
    private val planExecutor: PlanExecutor,
    /** capability 可用工具注册表，封装草稿、确认意图、图差异与代码读取工具。 */
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        listOf(
            GetDraftWorkbenchTool(DraftToolFacade()),
            GetConfirmedIntentTool(DraftToolFacade()),
            GetGraphDiffTool(GraphToolFacade()),
            ReadSourceSnippetTool(CodeReadToolFacade()),
        ),
    ),
) : AgentCapability<PlanCapabilityInput, GenerationPlan> {
    /** capability 稳定标识，外部通过该字符串识别计划生成能力。 */
    override val capabilityId: String = "plan"

    /** 构造计划运行的初始状态，包含运行 ID、用户目标、预算与初始步号。 */
    override fun buildInitialState(input: PlanCapabilityInput, runtimeContext: AgentRuntimeContext): AgentRunState {
        return AgentRunState(
            runId = "plan-${UUID.randomUUID()}",
            capabilityId = capabilityId,
            phase = AgentRunPhase.CREATED,
            userGoal = "生成实现计划",
            budget = defaultBudget.withConfiguredRuntimeTimeout(input.settings),
            stepIndex = 0,
            artifactRefs = emptyList(),
        )
    }

    /** 声明本 capability 运行期间允许调用的工具名称集合。 */
    override fun allowedTools(input: PlanCapabilityInput): Set<String> {
        return setOf("get_draft_workbench", "get_confirmed_intent", "get_graph_diff", "read_source_snippet")
    }

    /** 返回默认停止策略，由 coordinator 在每一步前评估是否结束运行。 */
    override fun stopPolicy(input: PlanCapabilityInput): StopPolicy = StopPolicy.default()

    /** 从运行末态的 PlanArtifact 中取出最终计划，缺失则视为运行异常。 */
    override fun finalize(runState: AgentRunState, runtimeContext: AgentRuntimeContext): GenerationPlan {
        val artifact = runState.artifactRefs
            .asSequence()
            .mapNotNull(runtimeContext.artifactStore::get)
            .filterIsInstance<PlanArtifact>()
            .lastOrNull()
            ?: error("计划运行结束时缺少 PlanArtifact")
        return artifact.plan
    }

    /** 创建分步执行器：依次读取草稿边界、确认意图、图差异、代码证据，最后调用执行器生成计划。 */
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

    /** 第 0 步：读取草稿工作台边界，确认后续证据读取的起点。 */
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
            context = runtimeContext.toolExecutionContext(
                snapshot = snapshot,
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

    /** 第 1 步：读取 confirmed intent，为空时直接进入下一步并继续生成计划。 */
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
            context = runtimeContext.toolExecutionContext(
                snapshot = snapshot,
                runBudget = state.budget,
            ),
        )
        val confirmedCount = result.payload["confirmedCount"] as? Int ?: 0
        @Suppress("UNCHECKED_CAST")
        val artifactRefs = result.payload["artifactRefs"] as? List<com.charmnight.linkgraph.llm.artifact.ArtifactRef> ?: emptyList()
        if (confirmedCount <= 0) {
            return AgentStepExecutionResult.continueWith(
                state.copy(
                    phase = AgentRunPhase.RUNNING,
                    budget = state.budget.recordStep(),
                    stepIndex = state.stepIndex + 1,
                    artifactRefs = state.artifactRefs + artifactRefs,
                    stepRecords = state.stepRecords + AgentStepRecord(
                        stepIndex = state.stepIndex,
                        phase = AgentRunPhase.RUNNING,
                        summary = "skip-confirmed-intent",
                        toolName = result.toolName,
                    ),
                    lastModelOutput = "当前没有已确认正式意图，计划将直接基于当前草稿快照和图差异生成。",
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

    /** 第 2 步：读取图差异，作为计划生成的重要证据来源。 */
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
            context = runtimeContext.toolExecutionContext(
                snapshot = snapshot,
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

    /** 第 3 步：基于 confirmed intent 的 edit scope 读取计划所需代码证据。 */
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
                    context = runtimeContext.toolExecutionContext(
                        snapshot = snapshot,
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

    /** 最后一步：聚合前面读取的证据，调用执行器生成计划并保存为 artifact。 */
    private fun generatePlan(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
        input: PlanCapabilityInput,
    ): AgentStepExecutionResult {
        return runCatching {
            runtimeContext.requireWithinDeadline()
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
            runtimeContext.requireWithinDeadline()
            val plan = planExecutor.invoke(
                input.copy(
                    planningPayload = input.planningPayload.copy(
                        confirmedChanges = confirmedChanges,
                        diff = runtimeDiff,
                        sourceContext = runtimeSourceContext,
                    ),
                ),
                runtimeContext,
                state,
            )
            runtimeContext.requireWithinDeadline()
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
                        summary = "generate-plan",
                    ),
                    lastModelOutput = plan.summary,
                ),
            )
        }.getOrElse { throwable ->
            if (throwable is AgentRuntimeDeadlineExceededException) {
                return AgentStepExecutionResult.fail(
                    state.copy(
                        phase = AgentRunPhase.FAILED,
                        budget = state.budget.recordStep(),
                        stepIndex = state.stepIndex + 1,
                        failureReason = AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED,
                        lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                    ),
                )
            }
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

    /** 从 artifact 列表中提取 confirmed intent 对应的草稿条目。 */
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

    /** 计划执行器接口，由上层注入真实实现（通常是远程模型调用）。 */
    fun interface PlanExecutor {
        /** 根据当前 capability 输入、运行上下文与状态生成实现计划。 */
        fun invoke(
            input: PlanCapabilityInput,
            runtimeContext: AgentRuntimeContext,
            state: AgentRunState,
        ): GenerationPlan
    }
}

/** 计划生成 capability 的输入。 */
internal data class PlanCapabilityInput(
    /** 已准备好的规划载荷。 */
    val planningPayload: PlanningInput,
    /** 当前 LLM 设置，用于统一 runtime 与远程请求超时。 */
    val settings: LinkGraphSettingsState = LinkGraphSettingsState(),
)
