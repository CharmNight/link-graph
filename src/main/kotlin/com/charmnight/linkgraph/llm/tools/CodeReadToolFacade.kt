package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceLocation
import com.charmnight.linkgraph.source.AttachedJarContentResolver
import com.charmnight.linkgraph.source.CompositeSourceContentResolver
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.foundation.truncateUtf8
import com.charmnight.linkgraph.foundation.utf8ByteCount
import com.charmnight.linkgraph.source.SourceContentResolver
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 统一处理代码读取。
 * 第一阶段优先复用 graph node 上已有 source metadata，读不到时再回退到显式文件路径读取。
 */
class CodeReadToolFacade(
    /** 图工具门面，复用其对图谱快照的查询能力。 */
    private val graphToolFacade: GraphToolFacade = GraphToolFacade(),
    /** 问答证据锚点解析器，负责把 nodeId 或签名映射回真实节点。 */
    private val anchorResolver: QaEvidenceAnchorResolver = QaEvidenceAnchorResolver(),
    /** 源码上下文收集器，用于在读取片段附近补充上下文行。 */
    private val sourceContextCollector: SourceContextCollector = SourceContextCollector(),
    /** 项目根路径下的可读文件访问策略，统一处理越权与符号链接等安全约束。 */
    private val projectRootFileAccessPolicy: ProjectRootFileAccessPolicy = ProjectRootFileAccessPolicy(),
) {
    /** 当前类的日志记录器。 */
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

    /**
     * 读取源码片段并返回更丰富的元数据，包括来源、是否反编译、VFS URL 等。
     * 优先使用 fallback snippet，其次尝试 jar/VFS 协议路径，最后回退到项目根路径下的文件读取。
     */
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
            val truncated = utf8ByteCount(it) > MAX_SOURCE_SNIPPET_BYTES
            return RichSourceSnippet(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                snippet = buildPlainSnippet(it, truncated),
                sourceDiagnostic = if (truncated) "SOURCE_SNIPPET_TRUNCATED" else null,
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
        return LoggedFailures.orNull(logger, "resolveSnippet read bounded file snippet") {
            readBoundedFileSnippet(path, filePath, startLine, endLine)
        }
    }

    private fun readBoundedFileSnippet(
        path: Path,
        filePath: String,
        startLine: Int?,
        endLine: Int?,
    ): RichSourceSnippet? {
        val firstLine = startLine?.coerceAtLeast(1) ?: 1
        val requestedLastLine = endLine?.coerceAtLeast(firstLine)
        val focusedLastLine = minOf(
            requestedLastLine ?: (firstLine + MAX_SOURCE_SNIPPET_LINES - 1),
            firstLine + MAX_SOURCE_SNIPPET_LINES - 1,
        )
        val includeContext = startLine != null && endLine != null
        val stopAfterLine = if (includeContext) focusedLastLine + SOURCE_NEIGHBOR_CONTEXT_LINES else focusedLastLine
        val imports = mutableListOf<String>()
        val classContext = mutableListOf<String>()
        val pendingAnnotations = mutableListOf<String>()
        val beforeFocus = ArrayDeque<String>()
        val focused = BoundedSnippetLines(maxBytes = MAX_SOURCE_SNIPPET_BYTES)
        val afterFocus = mutableListOf<String>()
        var lineNumber = 0
        var truncated = requestedLastLine != null && requestedLastLine > focusedLastLine
        var collectingClassContext = false

        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (line.trim().startsWith("import ")) {
                    imports += line
                }
                when {
                    lineNumber < firstLine -> {
                        updateClassContextBeforeFocus(
                            line = line,
                            classContext = classContext,
                            pendingAnnotations = pendingAnnotations,
                            isCollecting = collectingClassContext,
                            onCollectingChanged = { collectingClassContext = it },
                        )
                        if (includeContext) {
                            beforeFocus.addLast(line)
                            while (beforeFocus.size > SOURCE_NEIGHBOR_CONTEXT_LINES) {
                                beforeFocus.removeFirst()
                            }
                        }
                    }
                    lineNumber in firstLine..focusedLastLine -> {
                        if (!focused.add(line)) {
                            truncated = true
                            break
                        }
                    }
                    includeContext && lineNumber in (focusedLastLine + 1)..stopAfterLine -> afterFocus += line
                }
                if (lineNumber >= stopAfterLine) {
                    if (reader.readLine() != null && requestedLastLine == null) {
                        truncated = true
                    }
                    break
                }
            }
        }
        if (focused.isEmpty()) {
            return null
        }

        val focusedSnippet = focused.joinToString()
        val snippet = if (includeContext) {
            buildContextualSnippet(
                imports = imports,
                classContext = classContext,
                beforeFocus = beforeFocus.toList(),
                afterFocus = afterFocus,
                focusedSnippet = focusedSnippet,
                truncated = truncated,
            )
        } else {
            buildPlainSnippet(focusedSnippet, truncated)
        }
        return RichSourceSnippet(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            snippet = snippet,
        )
    }

    private fun buildContextualSnippet(
        imports: List<String>,
        classContext: List<String>,
        beforeFocus: List<String>,
        afterFocus: List<String>,
        focusedSnippet: String,
        truncated: Boolean,
    ): String {
        val neighborContext = (beforeFocus + afterFocus)
            .joinToString("\n")
            .trim()
            .takeIf(String::isNotBlank)
        val body = listOfNotNull(
            imports.distinct().joinToString("\n").takeIf(String::isNotBlank)?.let { "imports:\n$it" },
            classContext.joinToString("\n").trim().takeIf(String::isNotBlank)?.let { "class context:\n$it" },
            neighborContext?.let { "neighbor context:\n$it" },
            "current method:\n$focusedSnippet",
        ).joinToString("\n\n")
        return buildPlainSnippet(body, truncated)
    }

    private fun updateClassContextBeforeFocus(
        line: String,
        classContext: MutableList<String>,
        pendingAnnotations: MutableList<String>,
        isCollecting: Boolean,
        onCollectingChanged: (Boolean) -> Unit,
    ) {
        if (isCollecting) {
            classContext += line
            if (line.contains("{")) {
                onCollectingChanged(false)
            }
            return
        }
        val trimmed = line.trim()
        if (trimmed.startsWith("@")) {
            pendingAnnotations += line
            return
        }
        if (CLASS_DECLARATION_REGEX.containsMatchIn(line)) {
            classContext.clear()
            classContext += pendingAnnotations
            classContext += line
            pendingAnnotations.clear()
            onCollectingChanged(!line.contains("{"))
            return
        }
        pendingAnnotations.clear()
    }

    private fun buildPlainSnippet(
        snippet: String,
        truncated: Boolean,
    ): String {
        val exceedsByteLimit = utf8ByteCount(snippet) > MAX_SOURCE_SNIPPET_BYTES
        if (!truncated && !exceedsByteLimit) {
            return snippet
        }
        val suffix = "\n\n$SOURCE_SNIPPET_TRUNCATED_NOTICE"
        val bodyBudget = (MAX_SOURCE_SNIPPET_BYTES - utf8ByteCount(suffix)).coerceAtLeast(0)
        val boundedSnippet = truncateUtf8(snippet, bodyBudget).trimEnd()
        if (boundedSnippet.isEmpty()) {
            return SOURCE_SNIPPET_TRUNCATED_NOTICE
        }
        return "$boundedSnippet$suffix"
    }

    /** 读取源码片段失败时返回的原因字符串；可读到时返回 null，便于上层在 UI 中给出具体失败原因。 */
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

    /** 读取指定符号的源码片段并附带丰富元数据；优先命中架构索引，命中失败时回退到符号锚点解析。 */
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

    /** 直接从架构索引中按签名命中符号并读取对应源码片段。 */
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

    /** 返回符号源码读取失败的具体原因；命中成功时返回 null。 */
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

    /** 在索引中按多种匹配策略查找符号：精确 id、方法签名、字段、类、资源路径、去参数签名等。 */
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

    /** 读取架构索引中符号对应的源码片段，并补充符号 ID、种类等元数据。 */
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

    /** 直接按全限定名读取类或资源内容，主要用于绕过架构索引时的回退路径。 */
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

    /** 返回按全限定名读取失败的具体原因；命中成功时返回 null。 */
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

    /** 创建项目默认的源码内容解析器。 */
    private fun defaultResolver(project: Project): SourceContentResolver {
        return project.architectureIndexRuntime().sourceQuery()
    }

    /** 判断当前项目是否允许展开 JDK 库源码（受项目设置控制）。 */
    private fun allowJdkLibraryExpansion(project: Project): Boolean =
        LoggedFailures.orDefault(logger, "allowJdkLibraryExpansion settingsSnapshot", defaultValue = false) {
            project.architectureIndexRuntime().settingsSnapshot().allowJdkLibraryExpansion
        }

    /** 判断符号名是否属于 JDK 命名空间，用于默认禁止 JDK 源码展开的硬边界。 */
    private fun isJdkSymbolName(value: String): Boolean =
        value.startsWith("java.") ||
            value.startsWith("javax.") ||
            value.startsWith("jdk.") ||
            value.startsWith("sun.") ||
            value.startsWith("com.sun.")

    private companion object {
        private const val MAX_SOURCE_SNIPPET_LINES = 400
        private const val MAX_SOURCE_SNIPPET_BYTES = 64 * 1024
        private const val SOURCE_NEIGHBOR_CONTEXT_LINES = 3
        private const val SOURCE_SNIPPET_TRUNCATED_NOTICE = "片段已截断：最多返回 400 行或 64 KiB。"
        private val CLASS_DECLARATION_REGEX = Regex("\\b(class|interface|enum|record)\\b")

        private class BoundedSnippetLines(
            private val maxBytes: Int,
        ) {
            private val lines = mutableListOf<String>()
            private var byteCount = 0

            fun isEmpty(): Boolean = lines.isEmpty()

            fun add(line: String): Boolean {
                val separatorBytes = if (lines.isEmpty()) 0 else 1
                val availableBytes = maxBytes - byteCount - separatorBytes
                if (availableBytes <= 0) {
                    return false
                }
                val lineBytes = utf8ByteCount(line)
                if (lineBytes <= availableBytes) {
                    lines += line
                    byteCount += separatorBytes + lineBytes
                    return true
                }
                lines += truncateUtf8(line, availableBytes)
                byteCount = maxBytes
                return false
            }

            fun joinToString(): String = lines.joinToString("\n")
        }
    }
}

/**
 * 表示一次源码读取的丰富结果。
 * 比基础 SourceSnippetContext 多携带了来源、是否反编译、VFS URL、源码诊断信息和符号元数据，
 * 便于 runtime 与 UI 在不再次读取源码的前提下完整展示。
 */
data class RichSourceSnippet(
    /** 关联节点 ID。 */
    val nodeId: String? = null,
    /** 源码文件路径。 */
    val filePath: String,
    /** 片段起始行号。 */
    val startLine: Int? = null,
    /** 片段结束行号。 */
    val endLine: Int? = null,
    /** 真正读到的源码片段。 */
    val snippet: String,
    /** 源码来源标签，例如项目源码、依赖 jar 等。 */
    val origin: String? = null,
    /** 是否来自反编译内容。 */
    val decompiled: Boolean = false,
    /** IDEA VFS URL。 */
    val virtualFileUrl: String? = null,
    /** 源码读取过程中产生的诊断信息，便于排查。 */
    val sourceDiagnostic: String? = null,
    /** 关联的符号 ID。 */
    val symbolId: String? = null,
    /** 关联的符号种类标签。 */
    val symbolKind: String? = null,
) {
    /** 把当前对象转换为更基础的 SourceSnippetContext，便于复用现有数据模型。 */
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

/** 针对不同来源的解析器获取最近一次"读取失败原因"，便于上层在 UI 中直接展示。 */
private fun SourceContentResolver?.sourceUnavailableReason(): String? =
    when (this) {
        is CompositeSourceContentResolver -> lastUnavailableReason()
        is AttachedJarContentResolver -> lastUnavailableReason
        is IdeSourceContentResolver -> lastUnavailableReason
        else -> null
    }
