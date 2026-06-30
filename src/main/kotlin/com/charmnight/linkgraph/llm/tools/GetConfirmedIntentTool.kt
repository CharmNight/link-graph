package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

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
) : TypedAgentTool<GetConfirmedIntentInput>() {
    override val name: String = "get_confirmed_intent"
    override val description: String = "读取当前已确认草稿，作为计划和代码生成的唯一正式意图来源"

    override fun parseInput(raw: ToolInputPayload): GetConfirmedIntentInput = GetConfirmedIntentInput

    override fun invokeTyped(input: GetConfirmedIntentInput, context: ToolExecutionContext): ToolResult {
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

/** [GetConfirmedIntentTool] 的入参（工具不接受任何参数，用 object 表达）。 */
object GetConfirmedIntentInput
