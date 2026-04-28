package com.charmnight.linkgraph.llm.artifact

/**
 * 保存 runtime 实际读取到的代码证据。
 * 后续 capability 用它判断某个 existing-file draft 生成前是否真的读过目标代码。
 */
data class CodeEvidenceArtifact(
    override val artifactId: String,
    /** 关联节点。 */
    val nodeId: String,
    /** 证据文件路径。 */
    val filePath: String,
    /** 证据片段。 */
    val snippet: String,
    /** 起始行号。 */
    val startLine: Int? = null,
    /** 结束行号。 */
    val endLine: Int? = null,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.CODE_EVIDENCE

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "代码证据",
        description = "$filePath:${startLine ?: "?"}-${endLine ?: "?"}",
    )
}
