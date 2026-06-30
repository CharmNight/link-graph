package com.charmnight.linkgraph.agent.artifact

/**
 * 统一约束 runtime 产物的最小结构。
 *
 * 任何跨 step 复用的信息都应该先沉淀成 Artifact，再由后续 capability 显式消费。
 * 这种约定让 runtime 的状态变化始终是"产出 Artifact → 引用 Artifact"的形式，
 * 避免状态散落在 step 之间难以追踪。
 */
interface AgentArtifact {
    /** 产物稳定标识。一旦写入 store 不再变化，方便跨 step 引用。 */
    val artifactId: String

    /** 产物类型。便于按类型筛选、路由。 */
    val type: ArtifactType

    /** 产物摘要。给日志、UI 展示用，不包含完整内容。 */
    val summary: ArtifactSummary
}
