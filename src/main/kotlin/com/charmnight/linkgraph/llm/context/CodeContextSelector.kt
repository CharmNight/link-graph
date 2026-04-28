package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.llm.SourceSnippetContext

/**
 * 代码上下文选择器。
 */
class CodeContextSelector : ContextSelector<List<SourceSnippetContext>> {
    override fun select(input: List<SourceSnippetContext>): ContextSelectionResult {
        val sections = input.mapNotNull { snippet ->
            snippet.snippet?.takeIf { it.isNotBlank() }?.let { code ->
                "${snippet.filePath}:${snippet.startLine ?: "?"}-${snippet.endLine ?: "?"}\n$code"
            }
        }
        return ContextSelectionResult(
            sections = sections,
            summary = "code snippets=${sections.size}",
        )
    }
}
