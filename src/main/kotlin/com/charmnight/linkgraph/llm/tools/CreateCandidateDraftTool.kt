package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.agent.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.workbench.CandidateDraftChange

/**
 * 把候选草稿存入 artifact store。
 *
 * 这里只产生候选层产物，不会直接改写正式草稿箱状态。
 * 用户确认后才升级为 ConfirmedIntent；本工具仅完成"沉淀候选"这一步。
 */
class CreateCandidateDraftTool : TypedAgentTool<CreateCandidateDraftInput>() {
    override val name: String = "create_candidate_draft"
    override val description: String = "创建候选草稿 artifact，不触发确认"

    override fun parseInput(raw: ToolInputPayload): CreateCandidateDraftInput = CreateCandidateDraftInput(
        candidate = requireValue(raw, "candidate", CandidateDraftChange::class),
    )

    override fun invokeTyped(input: CreateCandidateDraftInput, context: ToolExecutionContext): ToolResult {
        val artifact = CandidateDraftArtifact(
            // 用 changeId 派生 artifactId，保证幂等
            artifactId = "candidate-${input.candidate.changeId}",
            candidate = input.candidate,
        )
        val ref = context.artifactStore.save(artifact)
        return ToolResult(
            toolName = name,
            payload = mapOf("artifactRef" to ref),
        )
    }
}

/** [CreateCandidateDraftTool] 的强类型入参。candidate 是模型构造的 [CandidateDraftChange] 结构。 */
data class CreateCandidateDraftInput(val candidate: CandidateDraftChange)
