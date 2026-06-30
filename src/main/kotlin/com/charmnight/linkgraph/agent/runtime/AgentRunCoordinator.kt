package com.charmnight.linkgraph.agent.runtime

import com.charmnight.linkgraph.agent.artifact.ArtifactStorePruner
import com.charmnight.linkgraph.agent.capability.AgentCapability

/**
 * 统一协调 capability 的受控运行。
 * M1 阶段先把旧单次 request 收进 runtime 壳，确保 runId、budget、step 和 failure reason 全部真实存在。
 */
class AgentRunCoordinator(
    /** 可选的统一 step executor，主要用于测试覆盖 step loop 行为。 */
    private val stepExecutor: StepExecutor? = null,
    /** 统一收口 runtime 产物生命周期。 */
    private val artifactStorePruner: ArtifactStorePruner = ArtifactStorePruner,
) {
    /**
     * 受控运行 capability，把输入逐步推进到完成、失败或停止。
     * 运行期间持续校验停止策略与 deadline，结束前会汇总 artifact 摘要并触发 pruner 清理。
     */
    fun <I, O> run(
        capability: AgentCapability<I, O>,
        input: I,
        runtimeContext: AgentRuntimeContext,
    ): AgentRunResult<O> {
        val stopPolicy = capability.stopPolicy(input)
        var state = capability.buildInitialState(input, runtimeContext).let { initial ->
            if (initial.phase == AgentRunPhase.CREATED) {
                initial.copy(phase = AgentRunPhase.RUNNING)
            } else {
                initial
            }
        }
        val runRuntimeContext = runtimeContext
            .withAllowedTools(capability.allowedTools(input))
            .withDeadline(state.budget)
        val executor = stepExecutor ?: capability.createStepExecutor(input)
        while (true) {
            stopPolicy.evaluate(state)?.let { reason ->
                val failedState = state.copy(
                    phase = AgentRunPhase.FAILED,
                    failureReason = reason,
                    lastModelOutput = if (reason == AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED) {
                        "runtime deadline exceeded"
                    } else {
                        state.lastModelOutput
                    },
                )
                return completeRun(
                    runtimeContext = runRuntimeContext,
                    finalState = failedState,
                    output = null,
                )
            }
            try {
                runRuntimeContext.requireWithinDeadline()
            } catch (deadlineExceeded: AgentRuntimeDeadlineExceededException) {
                return completeRun(
                    runtimeContext = runRuntimeContext,
                    finalState = state.copy(
                        phase = AgentRunPhase.FAILED,
                        failureReason = AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED,
                        lastModelOutput = deadlineExceeded.message ?: deadlineExceeded.javaClass.simpleName,
                    ),
                    output = null,
                )
            }
            when (val stepResult = runCatching { executor.executeNextStep(state, runRuntimeContext) }
                .getOrElse { throwable ->
                    if (throwable is AgentRuntimeDeadlineExceededException) {
                        AgentStepExecutionResult.fail(
                            state.copy(
                                phase = AgentRunPhase.FAILED,
                                failureReason = AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED,
                                lastModelOutput = throwable.message ?: throwable.javaClass.simpleName,
                            ),
                        )
                    } else {
                        throw throwable
                    }
                }) {
                is AgentStepExecutionResult.Continue -> {
                    state = normalizeRunningState(stepResult.state)
                }

                is AgentStepExecutionResult.Complete -> {
                    val completedState = normalizeCompletedState(stepResult.state)
                    val output = runCatching {
                        capability.finalize(completedState, runRuntimeContext)
                    }.getOrElse {
                        val failedState = completedState.copy(
                            phase = AgentRunPhase.FAILED,
                            failureReason = AgentRunFailureReason.FINALIZATION_FAILED,
                            lastModelOutput = it.message ?: it.javaClass.simpleName,
                        )
                        return completeRun(
                            runtimeContext = runRuntimeContext,
                            finalState = failedState,
                            output = null,
                        )
                    }
                    return completeRun(
                        runtimeContext = runRuntimeContext,
                        finalState = completedState,
                        output = output,
                    )
                }

                is AgentStepExecutionResult.Fail -> {
                    val failedState = stepResult.state.copy(
                        phase = AgentRunPhase.FAILED,
                        failureReason = stepResult.state.failureReason ?: AgentRunFailureReason.CAPABILITY_EXECUTION_FAILED,
                    )
                    return completeRun(
                        runtimeContext = runRuntimeContext,
                        finalState = failedState,
                        output = null,
                    )
                }
            }
        }
    }

    /** 汇总 artifact 摘要、清理过期产物并构造 capability 运行结果。 */
    private fun <O> completeRun(
        runtimeContext: AgentRuntimeContext,
        finalState: AgentRunState,
        output: O?,
    ): AgentRunResult<O> {
        val artifactSummaries = summarizeArtifacts(finalState, runtimeContext)
        artifactStorePruner.pruneAfterRun(
            artifactStore = runtimeContext.artifactStore,
            currentArtifactIds = finalState.artifactRefs.mapTo(linkedSetOf()) { ref -> ref.artifactId },
        )
        return AgentRunResult(
            finalState = finalState,
            artifactSummaries = artifactSummaries,
            output = output,
        )
    }

    /** 把 CREATED 状态归一化为 RUNNING，确保 runtime 主循环始终在运行态推进。 */
    private fun normalizeRunningState(state: AgentRunState): AgentRunState {
        return if (state.phase == AgentRunPhase.CREATED) {
            state.copy(phase = AgentRunPhase.RUNNING)
        } else {
            state
        }
    }

    /** 把状态归一化为 SUCCEEDED，便于 finalize 阶段统一处理成功路径。 */
    private fun normalizeCompletedState(state: AgentRunState): AgentRunState {
        return if (state.phase == AgentRunPhase.SUCCEEDED) {
            state
        } else {
            state.copy(phase = AgentRunPhase.SUCCEEDED)
        }
    }

    /** 把预算中的运行时间上限换算成绝对 deadline 时间戳，注入到 runtime 上下文中。 */
    private fun AgentRuntimeContext.withDeadline(budget: RunBudget): AgentRuntimeContext {
        return copy(
            deadlineEpochMillis = budget.startedAtEpochMillis + budget.maxRuntimeSeconds.toLong() * 1_000L,
        )
    }

    /**
     * workflow 只消费最小产物摘要，不直接接触 artifact store 细节。
     * 这样既能满足 UI 展示诉求，也不把 runtime 内部对象重新耦合回入口编排层。
     */
    private fun summarizeArtifacts(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): List<AgentRunArtifactSummary> {
        return state.artifactRefs.mapNotNull { ref ->
            val artifact = runtimeContext.artifactStore.get(ref) ?: return@mapNotNull null
            AgentRunArtifactSummary(
                artifactId = artifact.artifactId,
                artifactType = artifact.type.name,
                title = artifact.summary.title,
                description = artifact.summary.description,
            )
        }
    }
}
