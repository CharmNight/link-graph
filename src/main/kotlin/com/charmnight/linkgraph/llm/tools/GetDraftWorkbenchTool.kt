package com.charmnight.linkgraph.llm.tools

/**
 * 读取当前草稿箱状态。
 *
 * 同时返回候选草稿与已确认草稿，并同步到产物仓库（返回 artifactRefs）。
 * 让模型能一眼看到草稿箱全貌，避免漏掉已经确认过的意图。
 *
 * @param draftToolFacade 草稿工具外观
 */
class GetDraftWorkbenchTool(
    private val draftToolFacade: DraftToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_draft_workbench"

    /** 工具职责说明。 */
    override val description: String = "读取当前候选草稿与已确认草稿摘要"

    /**
     * @param input 工具输入（本工具不读取参数）
     * @param context 工具执行上下文
     * @return payload 包含候选/已确认草稿列表、计数与产物引用
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val candidates = draftToolFacade.candidateDrafts(context.snapshot)
        val confirmed = draftToolFacade.confirmedIntents(context.snapshot)
        // 同步到产物仓库，让后续 step 能通过引用读到
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
