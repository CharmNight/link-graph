package com.charmnight.linkgraph.llm.context

import com.charmnight.linkgraph.agent.model.SourceSnippetContext

/**
 * 代码上下文选择器。
 *
 * 把若干源码片段转换为 LLM 上下文：每个片段渲染为
 * "文件路径:起止行" + 代码块的格式。空 snippet 被丢弃，避免污染上下文。
 * 最终输出既包含定位信息（让模型可以引用）又包含代码本身。
 */
class CodeContextSelector : ContextSelector<List<SourceSnippetContext>> {
    /**
     * @param input 源码片段列表
     * @return 包含有效代码段落与计数摘要的选择结果
     */
    override fun select(input: List<SourceSnippetContext>): ContextSelectionResult {
        val sections = input.mapNotNull { snippet ->
            // snippet 内容为空时跳过
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
