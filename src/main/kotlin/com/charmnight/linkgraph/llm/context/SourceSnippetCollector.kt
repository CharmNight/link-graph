package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.llm.SourceSnippetContext

/**
 * 统一收敛源码片段列表。
 */
class SourceSnippetCollector {
    fun collect(snippets: List<SourceSnippetContext>): List<SourceSnippetContext> {
        return snippets.distinctBy { snippet ->
            "${snippet.filePath}:${snippet.startLine}:${snippet.endLine}:${snippet.nodeId}"
        }
    }
}
