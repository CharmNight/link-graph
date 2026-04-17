package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前草稿箱状态。
 */
class GetDraftWorkbenchTool(
    private val draftToolFacade: DraftToolFacade,
) : AgentTool {
    override val name: String = "get_draft_workbench"

    override val description: String = "读取当前候选草稿与已确认草稿摘要"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val candidates = draftToolFacade.candidateDrafts(context.snapshot)
        val confirmed = draftToolFacade.confirmedIntents(context.snapshot)
        val candidateRefs = draftToolFacade.syncCandidateDrafts(context.snapshot, context.artifactStore)
        val confirmedRefs = draftToolFacade.syncConfirmedIntents(context.snapshot, context.artifactStore)
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "candidateDrafts" to candidates,
                "confirmedIntents" to confirmed,
                "candidateCount" to candidates.size,
                "confirmedCount" to confirmed.size,
                "candidateArtifactRefs" to candidateRefs,
                "confirmedArtifactRefs" to confirmedRefs,
            ),
        )
    }
}
