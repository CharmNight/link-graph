package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 草稿上下文选择器。
 *
 * 把已确认草稿条目列表转换为 LLM 上下文片段：
 * 每条草稿渲染为"title：reason"形式的段落，缺失 reason 时只保留 title。
 * 这样模型在生成后续内容时能感知到用户已经确认了哪些意图。
 */
class DraftContextSelector : ContextSelector<List<DraftWorkbenchEntry>> {
    /**
     * @param input 已确认的草稿条目列表
     * @return 包含每条草稿文本与计数摘要的选择结果
     */
    override fun select(input: List<DraftWorkbenchEntry>): ContextSelectionResult {
        val sections = input.map { entry ->
            buildString {
                append(entry.title)
                // reason 非空时附加，避免出现 "title："
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
