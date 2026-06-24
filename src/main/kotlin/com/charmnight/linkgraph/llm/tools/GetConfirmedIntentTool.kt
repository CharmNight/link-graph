package com.charmnight.linkgraph.llm.tools

/**
 * 读取已确认正式意图。
 *
 * 计划与代码生成阶段必须基于"已确认意图"而非候选草稿。
 * 本工具把用户在草稿工作台确认过的条目读出来，同时把它们同步到产物仓库
 * （让后续 step 可以通过 ArtifactRef 引用），确保跨 step 一致。
 *
 * @param draftToolFacade 草稿工具外观，封装对工作台状态的访问
 */
class GetConfirmedIntentTool(
    private val draftToolFacade: DraftToolFacade,
) : AgentTool {
    /** 工具稳定名称。 */
    override val name: String = "get_confirmed_intent"

    /** 工具职责说明。 */
    override val description: String = "读取当前已确认草稿，作为计划和代码生成的唯一正式意图来源"

    /**
     * @param input 工具输入（本工具不读取任何参数）
     * @param context 工具执行上下文，提供快照与产物仓库
     * @return payload 中包含 confirmedIntents 列表、计数、产物引用
     */
    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val confirmed = draftToolFacade.confirmedIntents(context.snapshot)
        // 同步到产物仓库：让后续 step 能通过引用读到这些意图
        val artifactRefs = draftToolFacade.syncConfirmedIntents(context.snapshot, context.artifactStore)
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "confirmedIntents" to confirmed,
                "confirmedCount" to confirmed.size,
                "artifactRefs" to artifactRefs,
            ),
        )
    }
}
