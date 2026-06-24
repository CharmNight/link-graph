package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 图上下文选择器。
 *
 * 把图文档转换为 LLM 上下文片段。当前实现非常简单：只给出节点和边的数量统计，
 * 用于让模型感知图规模。后续可以扩展为按节点重要性抽取若干代表性节点。
 */
class GraphContextSelector : ContextSelector<GraphDocument> {
    /**
     * @param input 待选择上下文的图文档
     * @return 包含节点/边数量段落与一句话摘要的选择结果
     */
    override fun select(input: GraphDocument): ContextSelectionResult {
        val sections = buildList {
            if (input.nodes.isNotEmpty()) {
                add("nodes=${input.nodes.size}")
            }
            if (input.edges.isNotEmpty()) {
                add("edges=${input.edges.size}")
            }
        }
        return ContextSelectionResult(
            sections = sections,
            summary = "graph nodes=${input.nodes.size}, edges=${input.edges.size}",
        )
    }
}
