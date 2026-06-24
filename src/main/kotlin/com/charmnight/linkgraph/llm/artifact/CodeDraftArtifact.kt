package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.codegen.CodeGenerationResult

/**
 * 保存 runtime 生成出的代码草稿结果。
 *
 * 代码阶段产出该产物后，下游的"应用 diff"等动作通过 artifactId 引用本产物，
 * 不再依赖临时变量，保证产物有完整 lineage。
 */
data class CodeDraftArtifact(
    override val artifactId: String,
    /** 代码草稿结果。 */
    val draftResult: CodeGenerationResult,
) : AgentArtifact {
    /** 产物类型固定为代码草稿。 */
    override val type: ArtifactType = ArtifactType.CODE_DRAFT

    /** 摘要给出 drafts 与 warnings 的数量，便于一眼判断本轮产出规模。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "代码草稿",
        description = "drafts=${draftResult.drafts.size}, warnings=${draftResult.warnings.size}",
    )
}
