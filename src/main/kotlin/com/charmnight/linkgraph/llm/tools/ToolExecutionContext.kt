package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.intellij.openapi.project.Project

/**
 * Tool 执行时允许访问的上下文。
 * 与 runtimeContext 一样，它只暴露读能力和产物存储，不承载越权入口。
 */
data class ToolExecutionContext(
    /** 当前项目。 */
    val project: Project,
    /** 当前只读快照。 */
    val snapshot: ToolGraphSnapshot,
    /** 当前产物仓库。 */
    val artifactStore: ArtifactStore,
    /** 当前预算快照。 */
    val runBudget: RunBudget,
    /** 当前 capability 允许调用的工具名；null 表示旧调用点尚未启用 runtime 策略。 */
    val allowedToolNames: Set<String>? = null,
    /** 受控 graph edit 执行入口。为空表示当前 runtime 不允许写图。 */
    val graphEditRequestExecutor: GraphEditRequestExecutor? = null,
)
