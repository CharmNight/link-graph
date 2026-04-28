package com.charmnight.linkgraph.llm.artifact

import java.util.concurrent.ConcurrentHashMap

/**
 * 第一阶段内存版产物仓库。
 * 目标是先让 runtime 脱离“全靠会话历史传值”的模式，后续若需要持久化再替换实现。
 */
class InMemoryArtifactStore : ArtifactStore {
    private val artifacts = ConcurrentHashMap<String, AgentArtifact>()

    override fun save(artifact: AgentArtifact): ArtifactRef {
        artifacts[artifact.artifactId] = artifact
        return ArtifactRef(
            artifactId = artifact.artifactId,
            type = artifact.type,
        )
    }

    override fun get(ref: ArtifactRef): AgentArtifact? = artifacts[ref.artifactId]

    override fun get(artifactId: String): AgentArtifact? = artifacts[artifactId]

    override fun all(): List<AgentArtifact> = artifacts.values.sortedBy(AgentArtifact::artifactId)

    override fun byType(type: ArtifactType): List<AgentArtifact> {
        return all().filter { artifact -> artifact.type == type }
    }

    override fun remove(artifactId: String): Boolean {
        return artifacts.remove(artifactId) != null
    }
}
