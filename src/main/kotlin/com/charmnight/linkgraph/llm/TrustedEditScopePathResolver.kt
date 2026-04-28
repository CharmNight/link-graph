package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode

/**
 * 统一从本地可信上下文推导 edit scope 的文件与范围。
 * 远端 reference 只能补充行号，不能决定本地文件路径。
 */
class TrustedEditScopePathResolver {
    internal fun resolve(
        node: GraphNode,
        snippet: SourceSnippetContext?,
        reference: ResultEvidenceReference?,
    ): TrustedEditScopeLocation? {
        val filePath = snippet?.filePath
            ?: node.metadata["source.filePath"]
            ?: node.location?.substringBefore(':')
            ?: return null
        return TrustedEditScopeLocation(
            filePath = filePath,
            startOffset = snippet?.startOffset ?: node.metadata["source.startOffset"]?.toIntOrNull(),
            endOffset = snippet?.endOffset ?: node.metadata["source.endOffset"]?.toIntOrNull(),
            startLine = reference?.startLine ?: snippet?.startLine ?: node.metadata["source.startLine"]?.toIntOrNull(),
            endLine = reference?.endLine ?: snippet?.endLine ?: node.metadata["source.endLine"]?.toIntOrNull(),
        )
    }
}

internal data class TrustedEditScopeLocation(
    val filePath: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
)
