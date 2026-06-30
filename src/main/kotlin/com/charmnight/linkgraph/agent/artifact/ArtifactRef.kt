package com.charmnight.linkgraph.agent.artifact

/**
 * 引用 runtime 中已经落库的中间产物。
 *
 * state 只持有 ref，不直接塞完整对象，避免运行态状态继续膨胀。
 * 真正需要内容时由调用方通过 artifactId 去 [ArtifactStore] 取回，
 * 这种"引用 vs 内容"分离让状态的序列化与比较都很轻量。
 */
data class ArtifactRef(
    /** 产物稳定标识。一旦写入 store，ID 就不应再变。 */
    val artifactId: String,
    /** 产物类型，便于后续按类型筛选。缺失时为 null 表示类型未知。 */
    val type: ArtifactType? = null,
)
