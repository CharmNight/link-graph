package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.project.Project

/**
 * runtime 执行时可安全访问的宿主上下文。
 * 它只提供项目、状态快照读取和 artifact 存储，不直接暴露写 UI、写文件等越权能力。
 */
data class AgentRuntimeContext(
    /** 当前项目。 */
    val project: Project,
    /** 只读快照获取器。 */
    val snapshotSupplier: () -> com.charmnight.linkgraph.ui.GraphEditorStateSnapshot?,
    /** 本轮产物仓库。 */
    val artifactStore: ArtifactStore,
)
