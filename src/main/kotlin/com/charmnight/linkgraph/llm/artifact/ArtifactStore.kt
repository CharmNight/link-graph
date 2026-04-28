package com.charmnight.linkgraph.llm.artifact

/**
 * 管理 Agent Run 的结构化中间产物。
 * 第一阶段先使用内存实现，后续 capability 和 tool 通过统一接口读写，避免直接共享大对象。
 */
interface ArtifactStore {
    /** 保存产物并返回引用。 */
    fun save(artifact: AgentArtifact): ArtifactRef

    /** 按引用读取产物。 */
    fun get(ref: ArtifactRef): AgentArtifact?

    /** 按 ID 读取产物。 */
    fun get(artifactId: String): AgentArtifact?

    /** 列出当前所有产物，主要供调试与测试使用。 */
    fun all(): List<AgentArtifact>

    /** 按类型过滤产物。 */
    fun byType(type: ArtifactType): List<AgentArtifact>

    /** 按 ID 删除产物。 */
    fun remove(artifactId: String): Boolean
}
