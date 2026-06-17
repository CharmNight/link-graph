package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.tools.ToolExecutionContext
import com.charmnight.linkgraph.llm.tools.ToolGraphSnapshot
import com.intellij.openapi.project.Project

/**
 * runtime 执行时可安全访问的宿主上下文。
 * 它只提供项目、状态快照读取和 artifact 存储，不直接暴露写 UI、写文件等越权能力。
 */
data class AgentRuntimeContext(
    /** 当前项目。 */
    val project: Project,
    /** 只读快照获取器。 */
    val snapshotSupplier: () -> ToolGraphSnapshot?,
    /** 本轮产物仓库。 */
    val artifactStore: ArtifactStore,
    /** 本轮 runtime 的截止时间，达到后 step 应尽早协作式停止。 */
    val deadlineEpochMillis: Long? = null,
    /** 当前 capability 明确允许调用的工具名；为空集合表示禁止调用任何工具。 */
    val allowedToolNames: Set<String>? = null,
    /** 当前 runtime 允许的受控图编辑入口；为空表示工具不能写图。 */
    val graphEditRequestExecutor: GraphEditRequestExecutor? = null,
) {
    fun isDeadlineExceeded(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        return deadlineEpochMillis?.let { deadline -> nowEpochMillis >= deadline } ?: false
    }

    fun remainingRuntimeSeconds(nowEpochMillis: Long = System.currentTimeMillis()): Int? {
        val deadline = deadlineEpochMillis ?: return null
        return (((deadline - nowEpochMillis).coerceAtLeast(0L) + 999L) / 1_000L).toInt()
    }

    fun requireWithinDeadline(nowEpochMillis: Long = System.currentTimeMillis()) {
        if (isDeadlineExceeded(nowEpochMillis)) {
            throw AgentRuntimeDeadlineExceededException()
        }
    }

    fun withAllowedTools(allowedToolNames: Set<String>): AgentRuntimeContext =
        copy(allowedToolNames = allowedToolNames)

    fun toolExecutionContext(
        snapshot: ToolGraphSnapshot,
        runBudget: RunBudget,
    ): ToolExecutionContext =
        ToolExecutionContext(
            project = project,
            snapshot = snapshot,
            artifactStore = artifactStore,
            runBudget = runBudget,
            allowedToolNames = allowedToolNames,
            graphEditRequestExecutor = graphEditRequestExecutor,
        )
}

class AgentRuntimeDeadlineExceededException : RuntimeException("runtime deadline exceeded")
