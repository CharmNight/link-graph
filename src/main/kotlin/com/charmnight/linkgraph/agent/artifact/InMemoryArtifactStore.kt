package com.charmnight.linkgraph.agent.artifact

import java.util.concurrent.ConcurrentHashMap

/**
 * 第一阶段内存版产物仓库。
 *
 * 目标是先让 runtime 脱离"全靠会话历史传值"的模式，
 * 后续若需要持久化（例如写文件、跨会话保留）再替换实现，调用方接口不变。
 * 使用 ConcurrentHashMap 保证多线程读写安全。
 */
class InMemoryArtifactStore : ArtifactStore {
    /** artifactId → 产物对象的并发安全映射。 */
    private val artifacts = ConcurrentHashMap<String, AgentArtifact>()

    /**
     * 保存产物并返回引用。
     * 同 ID 写入会覆盖旧值，实现"幂等更新"。
     */
    override fun save(artifact: AgentArtifact): ArtifactRef {
        artifacts[artifact.artifactId] = artifact
        return ArtifactRef(
            artifactId = artifact.artifactId,
            type = artifact.type,
        )
    }

    /** 按引用取产物；不存在返回 null。 */
    override fun get(ref: ArtifactRef): AgentArtifact? = artifacts[ref.artifactId]

    /** 按 ID 取产物；不存在返回 null。 */
    override fun get(artifactId: String): AgentArtifact? = artifacts[artifactId]

    /** 列出所有产物，按 artifactId 排序保证多次调用顺序稳定。 */
    override fun all(): List<AgentArtifact> = artifacts.values.sortedBy(AgentArtifact::artifactId)

    /** 按类型过滤产物。 */
    override fun byType(type: ArtifactType): List<AgentArtifact> {
        return all().filter { artifact -> artifact.type == type }
    }

    /** 按 ID 删除产物。返回是否真正删除了某条目。 */
    override fun remove(artifactId: String): Boolean {
        return artifacts.remove(artifactId) != null
    }
}
