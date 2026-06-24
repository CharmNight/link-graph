package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.llm.SourceSnippetContext

/**
 * 统一收敛源码片段列表。
 *
 * 在构造 LLM 上下文时，多个上游（不同解析器、不同视图）可能给出重复的源码片段
 * （同一段代码被多次引用）。本类负责按唯一键去重，避免把相同片段重复塞进 prompt。
 */
class SourceSnippetCollector {
    /**
     * 把候选片段按"文件路径+起止行+节点 ID"做唯一性去重。
     *
     * @param snippets 原始候选片段列表
     * @return 去重后的片段列表，保留首次出现的版本
     */
    fun collect(snippets: List<SourceSnippetContext>): List<SourceSnippetContext> {
        return snippets.distinctBy { snippet ->
            "${snippet.filePath}:${snippet.startLine}:${snippet.endLine}:${snippet.nodeId}"
        }
    }
}
