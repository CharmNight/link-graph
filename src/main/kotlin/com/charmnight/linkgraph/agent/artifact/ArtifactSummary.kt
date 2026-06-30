package com.charmnight.linkgraph.agent.artifact

/**
 * 产物摘要只暴露给 runtime 与日志，用于在不展开完整内容时快速判断当前 run 已沉淀了什么证据。
 *
 * 完整产物可能很大（一段 JSON、一份完整的图快照等），在日志摘要、列表展示等场景下
 * 只需要标题与简介即可。本类就是这种"轻量视图"的载体。
 */
data class ArtifactSummary(
    /** 摘要标题：人类可读的简短描述，例如"调用链证据"、"风险点列表"。 */
    val title: String,
    /** 摘要说明：可选的详细描述，可为 null 表示只有标题。 */
    val description: String? = null,
)
