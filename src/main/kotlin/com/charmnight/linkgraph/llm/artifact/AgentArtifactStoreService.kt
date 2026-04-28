package com.charmnight.linkgraph.llm.artifact

import com.intellij.openapi.components.Service

/**
 * 项目级共享 runtime artifact store。
 * 问答、草稿确认、计划和代码阶段通过同一个 store 传递结构化产物，避免每次 run 都丢失 lineage。
 */
@Service(Service.Level.PROJECT)
class AgentArtifactStoreService {
    val artifactStore: ArtifactStore = InMemoryArtifactStore()
}
