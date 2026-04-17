package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.CandidateDraftArtifact
import com.charmnight.linkgraph.workbench.CandidateDraftChange

/**
 * 把候选草稿存入 artifact store。
 * 这里只产生候选层产物，不会直接改写正式草稿箱状态。
 */
class CreateCandidateDraftTool : AgentTool {
    override val name: String = "create_candidate_draft"

    override val description: String = "创建候选草稿 artifact，不触发确认"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val candidate = input["candidate"] as? CandidateDraftChange
            ?: return ToolResult(
                toolName = name,
                success = false,
                errorMessage = "candidate 不能为空",
            )
        val artifact = CandidateDraftArtifact(
            artifactId = "candidate-${candidate.changeId}",
            candidate = candidate,
        )
        val ref = context.artifactStore.save(artifact)
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "artifactRef" to ref,
            ),
        )
    }
}
