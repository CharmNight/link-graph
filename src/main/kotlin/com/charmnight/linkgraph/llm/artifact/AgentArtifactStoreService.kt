package com.charmnight.linkgraph.llm.artifact

import com.intellij.openapi.components.Service

/**
 * 项目级共享 runtime artifact store。
 *
 * 用 IntelliJ 的 @Service(PROJECT) 把同一个 [ArtifactStore] 暴露给项目内所有组件，
 * 让问答、草稿确认、计划生成、代码生成这四个阶段通过同一份存储传递结构化产物。
 *
 * 设计动机：之前每个阶段独立运行后会丢失"上游给我什么、我给下游什么"的 lineage；
 * 共享 store 后，每个阶段只需在产物落库时引用 [ArtifactRef]，下游就能按 id 取回完整内容。
 */
@Service(Service.Level.PROJECT)
class AgentArtifactStoreService {
    /** 实际承载产物存取的内存仓库；后续可替换为持久化实现而不影响调用方。 */
    val artifactStore: ArtifactStore = InMemoryArtifactStore()
}
