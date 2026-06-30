package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceFilePathOrLocationPath
import com.charmnight.linkgraph.model.sourceLocation

/**
 * 统一从本地可信上下文推导 edit scope 的文件与范围。
 *
 * 远端 reference 只能补充行号，不能决定本地文件路径。
 * 因此本解析器以节点本地的 sourceLocation / snippet 为主，reference 仅作为行号补充，
 * 避免远端幻觉导致 edit scope 落到不存在的文件。
 */
class TrustedEditScopePathResolver {
    /**
     * 解析 edit scope 的位置信息。
     *
     * @param node 目标节点；提供本地文件路径与 sourceLocation
     * @param snippet 本地代码片段；优先级高于节点本身的 sourceLocation
     * @param reference 远端引用；仅用其行号字段
     * @return 解析出的位置信息；无法确定文件路径时返回 null
     */
    internal fun resolve(
        node: GraphNode,
        snippet: SourceSnippetContext?,
        reference: ResultEvidenceReference?,
    ): TrustedEditScopeLocation? {
        val sourceLocation = node.sourceLocation()
        // 文件路径必须有可信来源（snippet 或节点本身），reference 不能决定路径
        val filePath = snippet?.filePath
            ?: node.sourceFilePathOrLocationPath()
            ?: return null
        return TrustedEditScopeLocation(
            filePath = filePath,
            // 偏移优先用 snippet（更精确），缺失时回退到 sourceLocation
            startOffset = snippet?.startOffset ?: sourceLocation.startOffset,
            endOffset = snippet?.endOffset ?: sourceLocation.endOffset,
            // 行号优先用 reference（远端可能更新），再回退到 snippet 与 sourceLocation
            startLine = reference?.startLine ?: snippet?.startLine ?: sourceLocation.startLine,
            endLine = reference?.endLine ?: snippet?.endLine ?: sourceLocation.endLine,
        )
    }
}

/**
 * 可信 edit scope 的位置信息。
 *
 * 字段都可选是为了兼容多种来源（snippet、reference、sourceLocation），
 * 任一来源缺失某字段时由其他来源补齐。
 */
internal data class TrustedEditScopeLocation(
    val filePath: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
)
