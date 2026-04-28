package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 保存已确认正式意图。
 * 计划与代码阶段只能消费这一层，不能越过草稿确认门槛直接使用候选变更。
 */
data class ConfirmedIntentArtifact(
    override val artifactId: String,
    /** 已确认草稿条目。 */
    val entry: DraftWorkbenchEntry,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.CONFIRMED_INTENT

    override val summary: ArtifactSummary = ArtifactSummary(
        title = entry.title.ifBlank { "已确认意图" },
        description = entry.reason.takeIf { it.isNotBlank() },
    )
}
