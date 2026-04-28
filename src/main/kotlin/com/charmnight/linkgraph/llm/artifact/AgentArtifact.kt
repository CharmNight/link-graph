package com.charmnight.linkgraph.llm.artifact

/**
 * 统一约束 runtime 产物的最小结构。
 * 任何跨 step 复用的信息都应该先沉淀成 Artifact，再由后续 capability 显式消费。
 */
interface AgentArtifact {
    /** 产物稳定标识。 */
    val artifactId: String

    /** 产物类型。 */
    val type: ArtifactType

    /** 产物摘要。 */
    val summary: ArtifactSummary
}
