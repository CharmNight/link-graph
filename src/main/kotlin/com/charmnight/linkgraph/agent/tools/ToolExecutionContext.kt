package com.charmnight.linkgraph.agent.tools

import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.agent.artifact.ArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.intellij.openapi.project.Project

/**
 * Tool 执行时允许访问的上下文。
 *
 * 与 runtimeContext 一样，它只暴露读能力和产物存储，不承载越权入口。
 * 例如工具不能直接拿到 logger、不能直接修改 UI、不能调用未在 [allowedToolNames] 中的其他工具。
 * 这种约束让 runtime 可以放心地让模型自由选择工具调用顺序。
 */
data class ToolExecutionContext(
    /** 当前项目。工具可通过它访问 IntelliJ 平台 API。 */
    val project: Project,
    /** 当前只读快照。包含图、状态等只读视图。 */
    val snapshot: ToolGraphSnapshot,
    /** 当前产物仓库。工具可写入中间产物供后续 step 消费。 */
    val artifactStore: ArtifactStore,
    /** 当前预算快照。工具执行前应检查是否还有配额。 */
    val runBudget: RunBudget,
    /** 当前 capability 允许调用的工具名；null 表示旧调用点尚未启用 runtime 策略。 */
    val allowedToolNames: Set<String>? = null,
    /** 受控 graph edit 执行入口。为空表示当前 runtime 不允许写图。 */
    val graphEditRequestExecutor: GraphEditRequestExecutor? = null,
)
