package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceLocation
import com.charmnight.linkgraph.source.AttachedJarContentResolver
import com.charmnight.linkgraph.source.CompositeSourceContentResolver
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.source.SourceContentResolver
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files

/**
 * 统一处理代码读取。
 * 第一阶段优先复用 graph node 上已有 source metadata，读不到时再回退到显式文件路径读取。
 */
class CodeReadToolFacade(
    private val graphToolFacade: GraphToolFacade = GraphToolFacade(),
    private val anchorResolver: QaEvidenceAnchorResolver = QaEvidenceAnchorResolver(),
    private val sourceContextCollector: SourceContextCollector = SourceContextCollector(),
    private val projectRootFileAccessPolicy: ProjectRootFileAccessPolicy = ProjectRootFileAccessPolicy(),
) {
    private val logger = Logger.getInstance(CodeReadToolFacade::class.java)
    /** 根据 nodeId 或 symbol 定位问答证据锚点，并保留投影到真实节点的映射轨迹。 */
    fun resolveEvidenceAnchor(
        snapshot: ToolGraphSnapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): QaEvidenceAnchorResolution {
        return anchorResolver.resolve(snapshot = snapshot, nodeId = nodeId, symbolSignature = symbolSignature)
    }

    /** 根据 nodeId 或 symbol 定位代码锚点。 */
    fun resolveAnchor(
        snapshot: ToolGraphSnapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): GraphNode? {
        return resolveEvidenceAnchor(snapshot, nodeId, symbolSignature).node
    }

    /** 读取指定代码片段，优先返回显式提供的 fallback snippet。 */
    fun readSourceSnippet(
        filePath: String,
        startLine: Int? = null,
        endLine: Int? = null,
        fallbackSnippet: String? = null,
        projectBasePath: String? = null,
        project: Project? = null,
    ): String? {
        return readSourceSnippetRich(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            fallbackSnippet = fallbackSnippet,
            projectBasePath = projectBasePath,
            project = project,
        )?.snippet
    }

    fun readSourceSnippetRich(
        filePath: String,
        startLine: Int? = null,
        endLine: Int? = null,
        fallbackSnippet: String? = null,
        projectBasePath: String? = null,
        project: Project? = null,
        resolver: SourceContentResolver? = project?.let(::defaultResolver),
    ): RichSourceSnippet? {
        fallbackSnippet?.takeIf { it.isNotBlank() }?.let {
            return RichSourceSnippet(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                snippet = it,
            )
        }
        if (filePath.contains("://") || filePath.contains("!/")) {
            resolver?.readSnippetByPath(filePath, startLine, endLine)?.let { content ->
                return RichSourceSnippet(
                    filePath = content.displayPath,
                    startLine = content.startLine ?: startLine,
                    endLine = content.endLine ?: endLine,
                    snippet = content.text,
                    origin = content.origin.name,
                    decompiled = content.decompiled,
                    virtualFileUrl = content.virtualFileUrl,
                    sourceDiagnostic = content.diagnostic,
                )
            }
            return null
        }
        val path = projectRootFileAccessPolicy.resolveReadablePath(filePath, projectBasePath, project)
            ?: return null
        LoggedFailures.orNull(logger, "resolveSnippet resolver.readSnippetByPath") {
            resolver?.readSnippetByPath(path.toString(), startLine, endLine)
        }?.let { content ->
            return RichSourceSnippet(
                filePath = content.displayPath,
                startLine = content.startLine ?: startLine,
                endLine = content.endLine ?: endLine,
                snippet = content.text,
                origin = content.origin.name,
                decompiled = content.decompiled,
                virtualFileUrl = content.virtualFileUrl,
                sourceDiagnostic = content.diagnostic,
            )
        }
        val lines = LoggedFailures.orNull(logger, "resolveSnippet Files.readAllLines") {
            Files.readAllLines(path)
        } ?: return null
        if (startLine == null || endLine == null) {
            return RichSourceSnippet(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                snippet = lines.joinToString("\n"),
            )
        }
        val fromIndex = (startLine - 1).coerceAtLeast(0)
        val toIndex = endLine.coerceAtMost(lines.size)
        if (fromIndex >= toIndex) {
            return null
        }
        val focusedSnippet = lines.subList(fromIndex, toIndex).joinToString("\n")
        return RichSourceSnippet(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = sourceContextCollector.collect(
                lines = lines,
                startLine = startLine,
                endLine = endLine,
                focusedSnippet = focusedSnippet,
            ),
        )
    }

    fun readSourceSnippetFailureReason(
        filePath: String,
        startLine: Int? = null,
        endLine: Int? = null,
        projectBasePath: String? = null,
        project: Project? = null,
        resolver: SourceContentResolver? = project?.let(::defaultResolver),
    ): String? {
        readSourceSnippetRich(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            projectBasePath = projectBasePath,
            project = project,
            resolver = resolver,
        )?.let { return null }
        return resolver.sourceUnavailableReason()
    }

    /** 根据 symbol 直接读取关联片段。 */
    fun readSymbol(
        snapshot: ToolGraphSnapshot,
        symbolSignature: String,
        fallbackSourceContexts: List<SourceSnippetContext> = emptyList(),
        projectBasePath: String? = null,
        project: Project? = null,
    ): SourceSnippetContext? {
        return readSymbolRich(snapshot, symbolSignature, fallbackSourceContexts, projectBasePath, project)
            ?.toSourceSnippetContext()
    }

    fun readSymbolRich(
        snapshot: ToolGraphSnapshot,
        symbolSignature: String,
        fallbackSourceContexts: List<SourceSnippetContext> = emptyList(),
        projectBasePath: String? = null,
        project: Project? = null,
    ): RichSourceSnippet? {
        readSymbolFromIndex(symbolSignature, project, projectBasePath)?.let { return it }
        val anchor = resolveAnchor(snapshot = snapshot, symbolSignature = symbolSignature) ?: return null
        val fallback = fallbackSourceContexts.firstOrNull { it.nodeId == anchor.id }
        val sourceLocation = anchor.sourceLocation()
        val filePath = sourceLocation.filePath ?: fallback?.filePath ?: return null
        val startLine = sourceLocation.startLine ?: fallback?.startLine
        val endLine = sourceLocation.endLine ?: fallback?.endLine
        val snippet = readSourceSnippetRich(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            fallbackSnippet = fallback?.snippet,
            projectBasePath = projectBasePath,
            project = project,
        ) ?: return null
        return snippet.copy(
            nodeId = anchor.id,
            filePath = snippet.filePath,
            startLine = snippet.startLine ?: startLine,
            endLine = snippet.endLine ?: endLine,
            origin = snippet.origin ?: fallback?.origin,
            decompiled = snippet.decompiled || fallback?.decompiled == true,
            virtualFileUrl = snippet.virtualFileUrl ?: sourceLocation.virtualFileUrl ?: fallback?.virtualFileUrl,
            sourceDiagnostic = snippet.sourceDiagnostic,
        )
    }

    private fun readSymbolFromIndex(
        symbolSignature: String,
        project: Project?,
        projectBasePath: String?,
    ): RichSourceSnippet? {
        project ?: return null
        val query = symbolSignature.trim().takeIf(String::isNotBlank) ?: return null
        val index = LoggedFailures.orNull(logger, "readSymbolFromIndex architectureIndexRuntime.index") {
            project.architectureIndexRuntime().index()
        }
            ?: return null
        val symbol = findIndexedSymbol(index, query)
            ?: return null
        return readIndexedSymbol(symbol, project, projectBasePath)
    }

    fun readSymbolFailureReason(
        symbolSignature: String,
        project: Project?,
        projectBasePath: String?,
    ): String? {
        project ?: return null
        val query = symbolSignature.trim().takeIf(String::isNotBlank) ?: return null
        val index = LoggedFailures.orNull(logger, "readSymbolFailureReason architectureIndexRuntime.index") {
            project.architectureIndexRuntime().index()
        }
        val symbol = index?.let { findIndexedSymbol(it, query) }
        val source = symbol?.source
        if (source != null) {
            return readSourceSnippetFailureReason(
                filePath = source.virtualFileUrl ?: source.displayPath,
                startLine = source.startLine,
                endLine = source.endLine,
                projectBasePath = projectBasePath,
                project = project,
            )
        }
        return readSymbolByQualifiedNameFailureReason(query, project)
    }

    private fun findIndexedSymbol(
        index: com.charmnight.linkgraph.architecture.ArchitectureGraphIndex,
        query: String,
    ): JvmSymbol? =
        index.findSymbol(query)
            ?: index.findMethod(query)
            ?: index.findField(query)
            ?: index.findClass(query)
            ?: index.symbolIndex.resourcesByPath[query]
            ?: query.substringBefore('(').takeIf { candidate -> candidate != query }?.let(index::findMethod)
            ?: query.substringBefore('#').takeIf { candidate -> candidate != query }?.let(index::findClass)

    private fun readIndexedSymbol(
        symbol: JvmSymbol,
        project: Project,
        projectBasePath: String?,
    ): RichSourceSnippet? {
        val source = symbol.source ?: return null
        val path = source.virtualFileUrl ?: source.displayPath
        val snippet = readSourceSnippetRich(
            filePath = path,
            startLine = source.startLine,
            endLine = source.endLine,
            projectBasePath = projectBasePath,
            project = project,
        ) ?: return null
        return snippet.copy(
            nodeId = symbol.id,
            filePath = snippet.filePath,
            startLine = snippet.startLine ?: source.startLine,
            endLine = snippet.endLine ?: source.endLine,
            origin = snippet.origin ?: symbol.origin.name,
            decompiled = snippet.decompiled || source.decompiled,
            virtualFileUrl = snippet.virtualFileUrl ?: source.virtualFileUrl,
            sourceDiagnostic = snippet.sourceDiagnostic,
            symbolId = symbol.id,
            symbolKind = when (symbol) {
                is JvmClassSymbol -> "class"
                is JvmMethodSymbol -> "method"
                is JvmFieldSymbol -> "field"
                is JvmResourceSymbol -> "resource"
                else -> "symbol"
            },
        )
    }

    fun readSymbolByQualifiedNameRich(
        symbolSignature: String,
        project: Project,
        projectBasePath: String? = project.basePath,
        resolver: SourceContentResolver = defaultResolver(project),
    ): RichSourceSnippet? {
        val query = symbolSignature.trim()
        if (isJdkSymbolName(query) && !allowJdkLibraryExpansion(project)) {
            return null
        }
        val content = resolver.readClassByQualifiedName(query)
            ?: resolver.readResourceByPath(query)
            ?: return null
        return RichSourceSnippet(
            nodeId = null,
            filePath = content.displayPath,
            startLine = content.startLine,
            endLine = content.endLine,
            snippet = content.text,
            origin = content.origin.name,
            decompiled = content.decompiled,
            virtualFileUrl = content.virtualFileUrl,
            sourceDiagnostic = content.diagnostic,
        )
    }

    fun readSymbolByQualifiedNameFailureReason(
        symbolSignature: String,
        project: Project,
        resolver: SourceContentResolver = defaultResolver(project),
    ): String? {
        val query = symbolSignature.trim()
        if (isJdkSymbolName(query) && !allowJdkLibraryExpansion(project)) {
            return "JDK_SOURCE_ACCESS_DISABLED"
        }
        resolver.readClassByQualifiedName(query)?.let { return null }
        resolver.readResourceByPath(query)?.let { return null }
        return resolver.sourceUnavailableReason()
    }

    private fun defaultResolver(project: Project): SourceContentResolver {
        return project.architectureIndexRuntime().sourceQuery()
    }

    private fun allowJdkLibraryExpansion(project: Project): Boolean =
        LoggedFailures.orDefault(logger, "allowJdkLibraryExpansion settingsSnapshot", defaultValue = false) {
            project.architectureIndexRuntime().settingsSnapshot().allowJdkLibraryExpansion
        }

    private fun isJdkSymbolName(value: String): Boolean =
        value.startsWith("java.") ||
            value.startsWith("javax.") ||
            value.startsWith("jdk.") ||
            value.startsWith("sun.") ||
            value.startsWith("com.sun.")
}

data class RichSourceSnippet(
    val nodeId: String? = null,
    val filePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val snippet: String,
    val origin: String? = null,
    val decompiled: Boolean = false,
    val virtualFileUrl: String? = null,
    val sourceDiagnostic: String? = null,
    val symbolId: String? = null,
    val symbolKind: String? = null,
) {
    fun toSourceSnippetContext(): SourceSnippetContext =
        SourceSnippetContext(
            nodeId = nodeId.orEmpty(),
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = snippet,
            origin = origin,
            decompiled = decompiled,
            virtualFileUrl = virtualFileUrl,
        )
}

private fun SourceContentResolver?.sourceUnavailableReason(): String? =
    when (this) {
        is CompositeSourceContentResolver -> lastUnavailableReason()
        is AttachedJarContentResolver -> lastUnavailableReason
        is IdeSourceContentResolver -> lastUnavailableReason
        else -> null
    }
