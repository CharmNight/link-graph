package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

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
) : TypedAgentTool<GetDraftWorkbenchInput>() {
    override val name: String = "get_draft_workbench"
    override val description: String = "读取当前候选草稿与已确认草稿摘要"

    override fun parseInput(raw: ToolInputPayload): GetDraftWorkbenchInput = GetDraftWorkbenchInput

    override fun invokeTyped(input: GetDraftWorkbenchInput, context: ToolExecutionContext): ToolResult {
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

/** [GetDraftWorkbenchTool] 的入参（工具不接受任何参数，用 object 表达）。 */
object GetDraftWorkbenchInput
