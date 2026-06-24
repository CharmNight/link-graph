package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 保存已确认正式意图。
 *
 * 候选草稿经用户确认后升级为已确认意图。计划与代码阶段只能消费这一层，
 * 不能越过草稿确认门槛直接使用候选变更——这种分层确保用户始终掌握
 * "什么内容会真正被生成"的最终决定权。
 */
data class ConfirmedIntentArtifact(
    override val artifactId: String,
    /** 已确认草稿条目。 */
    val entry: DraftWorkbenchEntry,
) : AgentArtifact {
    /** 产物类型固定为已确认意图。 */
    override val type: ArtifactType = ArtifactType.CONFIRMED_INTENT

    /** 摘要：取条目标题（空则用通用文案）；描述取条目 reason（若有）。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = entry.title.ifBlank { "已确认意图" },
        description = entry.reason.takeIf { it.isNotBlank() },
    )
}
