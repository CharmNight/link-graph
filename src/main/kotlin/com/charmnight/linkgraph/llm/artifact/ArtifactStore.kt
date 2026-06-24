package com.charmnight.linkgraph.llm.artifact

/**
 * 管理 Agent Run 的结构化中间产物。
 *
 * 第一阶段先使用内存实现，后续 capability 和 tool 通过统一接口读写，避免直接共享大对象。
 * 这种"通过 store 间接共享"的方式让 runtime 可以记录 lineage、做容量控制，
 * 也便于测试用桩替换。
 */
interface ArtifactStore {
    /**
     * 保存一个产物，返回可在后续 step 中引用的 [ArtifactRef]。
     *
     * @param artifact 待保存产物
     * @return 该产物的引用（含 artifactId 与类型）
     */
    fun save(artifact: AgentArtifact): ArtifactRef

    /** 按引用读取产物。引用失效或产物不存在时返回 null。 */
    fun get(ref: ArtifactRef): AgentArtifact?

    /** 按 ID 读取产物。不存在时返回 null。 */
    fun get(artifactId: String): AgentArtifact?

    /** 列出当前所有产物，主要供调试与测试使用。 */
    fun all(): List<AgentArtifact>

    /** 按类型过滤产物。便于 capability 查找特定种类的输入。 */
    fun byType(type: ArtifactType): List<AgentArtifact>

    /**
     * 按 ID 删除产物。
     *
     * @return 是否真正删除了（false 表示 ID 不存在）
     */
    fun remove(artifactId: String): Boolean
}
