package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.codegen.CodeGenerationResult

/**
 * 保存 runtime 生成出的代码草稿结果。
 */
data class CodeDraftArtifact(
    override val artifactId: String,
    /** 代码草稿结果。 */
    val draftResult: CodeGenerationResult,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.CODE_DRAFT

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "代码草稿",
        description = "drafts=${draftResult.drafts.size}, warnings=${draftResult.warnings.size}",
    )
}
