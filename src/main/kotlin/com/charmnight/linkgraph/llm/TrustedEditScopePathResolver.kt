package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceFilePathOrLocationPath
import com.charmnight.linkgraph.model.sourceLocation

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
        val sourceLocation = node.sourceLocation()
        val filePath = snippet?.filePath
            ?: node.sourceFilePathOrLocationPath()
            ?: return null
        return TrustedEditScopeLocation(
            filePath = filePath,
            startOffset = snippet?.startOffset ?: sourceLocation.startOffset,
            endOffset = snippet?.endOffset ?: sourceLocation.endOffset,
            startLine = reference?.startLine ?: snippet?.startLine ?: sourceLocation.startLine,
            endLine = reference?.endLine ?: snippet?.endLine ?: sourceLocation.endLine,
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
