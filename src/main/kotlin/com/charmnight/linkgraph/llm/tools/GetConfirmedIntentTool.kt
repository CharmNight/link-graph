package com.charmnight.linkgraph.llm.tools

/**
 * 读取已确认正式意图。
 */
class GetConfirmedIntentTool(
    private val draftToolFacade: DraftToolFacade,
) : AgentTool {
    override val name: String = "get_confirmed_intent"

    override val description: String = "读取当前已确认草稿，作为计划和代码生成的唯一正式意图来源"

    override fun invoke(
        input: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolResult {
        val confirmed = draftToolFacade.confirmedIntents(context.snapshot)
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
