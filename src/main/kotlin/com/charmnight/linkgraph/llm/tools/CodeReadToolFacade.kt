package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 统一处理代码读取。
 * 第一阶段优先复用 graph node 上已有 source metadata，读不到时再回退到显式文件路径读取。
 */
class CodeReadToolFacade(
    private val graphToolFacade: GraphToolFacade = GraphToolFacade(),
) {
    /** 根据 nodeId 或 symbol 定位代码锚点。 */
    fun resolveAnchor(
        snapshot: GraphEditorStateService.Snapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): GraphNode? {
        val graph = graphToolFacade.currentGraph(snapshot)
        return when {
            !nodeId.isNullOrBlank() -> graph.nodes.firstOrNull { it.id == nodeId }
            !symbolSignature.isNullOrBlank() -> graph.nodes.firstOrNull { it.signature == symbolSignature }
            else -> null
        }
    }

    /** 读取指定代码片段，优先返回显式提供的 fallback snippet。 */
    fun readSourceSnippet(
        filePath: String,
        startLine: Int? = null,
        endLine: Int? = null,
        fallbackSnippet: String? = null,
    ): String? {
        fallbackSnippet?.takeIf { it.isNotBlank() }?.let { return it }
        val path = runCatching { Paths.get(filePath) }.getOrNull() ?: return null
        if (!Files.exists(path)) {
            return null
        }
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return null
        if (startLine == null || endLine == null) {
            return lines.joinToString("\n")
        }
        val fromIndex = (startLine - 1).coerceAtLeast(0)
        val toIndex = endLine.coerceAtMost(lines.size)
        if (fromIndex >= toIndex) {
            return null
        }
        return lines.subList(fromIndex, toIndex).joinToString("\n")
    }

    /** 根据 symbol 直接读取关联片段。 */
    fun readSymbol(
        snapshot: GraphEditorStateService.Snapshot,
        symbolSignature: String,
        fallbackSourceContexts: List<SourceSnippetContext> = emptyList(),
    ): SourceSnippetContext? {
        val anchor = resolveAnchor(snapshot = snapshot, symbolSignature = symbolSignature) ?: return null
        val fallback = fallbackSourceContexts.firstOrNull { it.nodeId == anchor.id }
        val filePath = anchor.metadata["source.filePath"] ?: fallback?.filePath ?: return null
        val startLine = anchor.metadata["source.startLine"]?.toIntOrNull() ?: fallback?.startLine
        val endLine = anchor.metadata["source.endLine"]?.toIntOrNull() ?: fallback?.endLine
        return SourceSnippetContext(
            nodeId = anchor.id,
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = readSourceSnippet(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                fallbackSnippet = fallback?.snippet,
            ),
        )
    }
}
