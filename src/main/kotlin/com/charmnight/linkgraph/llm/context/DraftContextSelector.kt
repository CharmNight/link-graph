package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 草稿上下文选择器。
 */
class DraftContextSelector : ContextSelector<List<DraftWorkbenchEntry>> {
    override fun select(input: List<DraftWorkbenchEntry>): ContextSelectionResult {
        val sections = input.map { entry ->
            buildString {
                append(entry.title)
                if (entry.reason.isNotBlank()) {
                    append("：").append(entry.reason)
                }
            }
        }
        return ContextSelectionResult(
            sections = sections,
            summary = "confirmedDrafts=${input.size}",
        )
    }
}
