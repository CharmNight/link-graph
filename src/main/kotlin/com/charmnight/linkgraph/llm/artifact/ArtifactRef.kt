package com.charmnight.linkgraph.llm.artifact

/**
 * 引用 runtime 中已经落库的中间产物。
 * state 只持有 ref，不直接塞完整对象，避免运行态状态继续膨胀。
 */
data class ArtifactRef(
    /** 产物稳定标识。 */
    val artifactId: String,
    /** 产物类型，便于后续按类型筛选。 */
    val type: ArtifactType? = null,
)
