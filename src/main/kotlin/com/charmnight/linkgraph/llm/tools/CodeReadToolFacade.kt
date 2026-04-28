package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import java.nio.file.Files

/**
 * 统一处理代码读取。
 * 第一阶段优先复用 graph node 上已有 source metadata，读不到时再回退到显式文件路径读取。
 */
class CodeReadToolFacade(
    private val graphToolFacade: GraphToolFacade = GraphToolFacade(),
    private val projectRootFileAccessPolicy: ProjectRootFileAccessPolicy = ProjectRootFileAccessPolicy(),
) {
    /** 根据 nodeId 或 symbol 定位代码锚点。 */
    fun resolveAnchor(
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
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
        projectBasePath: String? = null,
    ): String? {
        fallbackSnippet?.takeIf { it.isNotBlank() }?.let { return it }
        val path = projectRootFileAccessPolicy.resolveReadablePath(filePath, projectBasePath)
            ?: return null
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
        snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
        symbolSignature: String,
        fallbackSourceContexts: List<SourceSnippetContext> = emptyList(),
        projectBasePath: String? = null,
    ): SourceSnippetContext? {
        val anchor = resolveAnchor(snapshot = snapshot, symbolSignature = symbolSignature) ?: return null
        val fallback = fallbackSourceContexts.firstOrNull { it.nodeId == anchor.id }
        val filePath = anchor.metadata["source.filePath"] ?: fallback?.filePath ?: return null
        val startLine = anchor.metadata["source.startLine"]?.toIntOrNull() ?: fallback?.startLine
        val endLine = anchor.metadata["source.endLine"]?.toIntOrNull() ?: fallback?.endLine
        val snippet = readSourceSnippet(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            fallbackSnippet = fallback?.snippet,
            projectBasePath = projectBasePath,
        ) ?: return null
        return SourceSnippetContext(
            nodeId = anchor.id,
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = snippet,
        )
    }
}
