package com.charmnight.linkgraph.agent.artifact

/**
 * 保存 runtime 实际读取到的代码证据。
 *
 * 后续 capability 用它判断某个 existing-file draft 生成前是否真的读过目标代码。
 * 这种"先取证再生成"的约束避免模型凭印象编造代码，保证生成的草稿有据可循。
 */
data class CodeEvidenceArtifact(
    override val artifactId: String,
    /** 关联节点。表示证据是为哪个图节点取的。 */
    val nodeId: String,
    /** 证据文件路径。 */
    val filePath: String,
    /** 证据片段。源码文本片段，作为生成依据。 */
    val snippet: String,
    /** 起始行号。 */
    val startLine: Int? = null,
    /** 结束行号。 */
    val endLine: Int? = null,
) : AgentArtifact {
    /** 产物类型固定为代码证据。 */
    override val type: ArtifactType = ArtifactType.CODE_EVIDENCE

    /** 摘要：用文件路径+行号区间定位证据来源。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "代码证据",
        description = "$filePath:${startLine ?: "?"}-${endLine ?: "?"}",
    )
}
