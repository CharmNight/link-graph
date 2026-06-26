package com.charmnight.linkgraph.review

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.review.git.GitChangeKind
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.charmnight.linkgraph.review.git.GitChangedFile
import com.charmnight.linkgraph.review.git.GitHunk
import com.charmnight.linkgraph.review.git.GitBaselineSymbolMapper
import com.charmnight.linkgraph.source.SourceContentResolver
import java.nio.file.Path

/**
 * 描述一段变更 diff 中具体修改区域的代码块。
 */
data class ChangedHunk(
    /** 保存当前文件路径（删除场景下为旧路径）。 */
    val filePath: String,
    /** 保存旧文件中代码块的起始行号。 */
    val oldStartLine: Int?,
    /** 保存旧文件中代码块的行数。 */
    val oldLineCount: Int?,
    /** 保存新文件中代码块的起始行号。 */
    val newStartLine: Int?,
    /** 保存新文件中代码块的行数。 */
    val newLineCount: Int?,
    /** 保存原始 hunk 头文本，用于前端展示和调试。 */
    val header: String,
    /** 保存重命名或删除前的旧路径。 */
    val oldFilePath: String? = null,
    /** 保存重命名或新增后的新路径。 */
    val newFilePath: String? = filePath,
    /** 保存 hunk 对应的变更类型，例如 HUNK_ADDED、HUNK_MODIFIED。 */
    val changeKind: String = "HUNK_MODIFIED",
)

/**
 * 描述代码审查场景下被识别为变更的符号。
 */
data class ChangedSymbol(
    /** 保存符号稳定标识，对应架构图中的 symbolId。 */
    val symbolId: String,
    /** 保存符号全限定名，便于展示。 */
    val qualifiedName: String,
    /** 保存符号所在源码文件路径。 */
    val filePath: String?,
    /** 保存符号在源码中的起始行号。 */
    val startLine: Int? = null,
    /** 保存符号在源码中的结束行号。 */
    val endLine: Int? = null,
    /** 保存与符号相关的代码块信息，便于精确展示具体修改位置。 */
    val hunk: ChangedHunk? = null,
    /** 保存符号的变更类型，例如 MODIFIED、ADDED、DELETED。 */
    val changeKind: String = "MODIFIED",
    /** 标记该符号只在基线版本中存在，无法在当前代码中定位。 */
    val baselineOnly: Boolean = false,
    /** 标记该符号的爆炸半径计算结果不完整（例如基线符号、外部符号）。 */
    val blastRadiusIncomplete: Boolean = false,
    /** 保存无法计算或解析时的原因说明。 */
    val unavailableReason: String? = null,
    /** 保存符号被判定为变更的具体原因，例如路径命中或代码块命中。 */
    val reason: String = "PATH_MATCHED_SYMBOL_FILE",
)

/**
 * 描述一组变更符号的影响范围，包含上下游依赖、相关测试以及受影响包/模块。
 */
data class BlastRadius(
    /** 保存本轮识别到的变更符号列表。 */
    val changedSymbols: List<ChangedSymbol>,
    /** 保存所有变更符号的并集上游依赖。 */
    val upstream: List<JvmSymbol>,
    /** 保存所有变更符号的并集下游依赖。 */
    val downstream: List<JvmSymbol>,
    /** 保存按变更符号分组后的上游依赖，便于精确展示每个变更的影响来源。 */
    val upstreamByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    /** 保存按变更符号分组后的下游依赖，便于精确展示每个变更的影响去向。 */
    val downstreamByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    /** 保存按变更符号分组后的相关测试符号列表。 */
    val relatedTestsByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    /** 保存按变更符号分组后的测试关联原因，键为符号 id，值为关联原因。 */
    val relatedTestReasonsByChangedSymbolId: Map<String, Map<String, String>> = emptyMap(),
    /** 保存受影响的 SPI provider 关系列表。 */
    val spiProviders: List<JvmRelation>,
    /** 保存受影响的反射目标关系列表。 */
    val reflectionTargets: List<JvmRelation>,
    /** 保存受影响的 ServiceLoader 加载关系列表。 */
    val serviceLoaderLoads: List<JvmRelation>,
    /** 保存受影响的代理目标关系列表。 */
    val proxyTargets: List<JvmRelation>,
    /** 保存与变更相关的所有测试关系列表。 */
    val testRelations: List<JvmRelation> = emptyList(),
    /** 保存去重后的相关测试符号列表。 */
    val relatedTests: List<JvmSymbol>,
    /** 保存相关测试符号与原因的对应关系，键为符号 id。 */
    val relatedTestReasons: Map<String, String> = emptyMap(),
    /** 保存受影响的去重后的包名列表。 */
    val affectedPackages: List<String> = emptyList(),
    /** 保存受影响的去重后的模块名列表。 */
    val affectedModules: List<String> = emptyList(),
)

/**
 * 描述代码审查使用的证据聚合结果，供 LLM 上下文或 UI 展示使用。
 */
data class ReviewEvidenceBundle(
    /** 保存识别到的变更符号列表。 */
    val changedSymbols: List<ChangedSymbol>,
    /** 保存爆炸半径整体信息。 */
    val blastRadius: BlastRadius,
    /** 保存扁平化的证据引用列表（变更符号 / 关系 / 上游下游符号三类，按 sealed DTO 区分）。 */
    val evidenceRefs: List<ReviewEvidenceRef>,
    /** 保存原始 Git 变更文件列表，便于前端对照查看。 */
    val gitChangedFiles: List<GitChangedFile> = emptyList(),
)

/**
 * 把 Git diff 文本/路径映射为架构图中的变更符号。
 *
 * 支持两种输入：完整 unified diff 文本（先解析成 [GitChangedFile]）和直接给定的路径或 hunk 列表。
 * 命中策略优先使用 hunk 与符号所在行范围重叠，否则退回到路径匹配。
 */
class GitDiffSymbolMapper(
    /** 保存当前架构图索引，用于在符号 id 与源码之间回查。 */
    private val index: ArchitectureGraphIndex,
) {
    /**
     * 根据输入路径或 diff 文本映射变更符号。
     */
    fun mapChangedFiles(paths: List<String>): List<ChangedSymbol> {
        // 当输入中包含完整 diff 文本时优先按结构化解析后再走结构化映射。
        val changeSet = paths
            .filter { text -> text.contains("diff --git") }
            .flatMap(GitChangeSetProvider::parseUnifiedDiff)
        if (changeSet.isNotEmpty()) {
            return mapChangeSet(changeSet)
        }
        // 没有完整 diff 时退回到纯 hunk 与路径匹配模式。
        val hunks = paths.flatMap(::parseUnifiedDiffHunks)
        val normalizedPaths = (
            paths +
                hunks.map(ChangedHunk::filePath) +
                hunks.mapNotNull(ChangedHunk::oldFilePath) +
                hunks.mapNotNull(ChangedHunk::newFilePath)
            )
            .flatMap(::pathCandidates)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedPaths.isEmpty() && hunks.isEmpty()) {
            return emptyList()
        }
        // 遍历图中全部符号，按路径命中或 hunk 行重叠判定是否为变更符号。
        return index.symbolIndex.symbolsById.values
            .mapNotNull { symbol ->
                val displayPath = symbol.source?.displayPath?.let(::normalize)
                    ?: return@mapNotNull null
                val matchingHunk = hunks.firstOrNull { hunk ->
                    hunk.pathCandidates().any { hunkPath -> pathsMatch(displayPath, normalize(hunkPath)) } &&
                        hunkTouchesSymbol(hunk, symbol)
                }
                val pathMatched = normalizedPaths.any { changedPath ->
                    pathsMatch(displayPath, changedPath)
                }
                if (!pathMatched && matchingHunk == null) {
                    return@mapNotNull null
                }
                ChangedSymbol(
                    symbolId = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    filePath = symbol.source?.displayPath,
                    startLine = symbol.source?.startLine,
                    endLine = symbol.source?.endLine,
                    hunk = matchingHunk,
                    changeKind = matchingHunk?.changeKind ?: "MODIFIED",
                    reason = if (matchingHunk != null) "HUNK_TOUCHES_SYMBOL_LINES" else "PATH_MATCHED_SYMBOL_FILE",
                )
            }
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)
    }

    /**
     * 把结构化的 [GitChangedFile] 列表映射为变更符号，命中策略同上。
     */
    fun mapChangeSet(files: List<GitChangedFile>): List<ChangedSymbol> {
        if (files.isEmpty()) {
            return emptyList()
        }
        // 先把每个文件下的 hunk 展开为统一变更块列表，便于符号按行匹配。
        val hunks = files.flatMap { file -> file.hunks.map { hunk -> hunk.toChangedHunk(file) } }
        val normalizedPaths = files
            .flatMap { file -> listOfNotNull(file.oldPath, file.newPath) }
            .flatMap(::pathCandidates)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
        return index.symbolIndex.symbolsById.values
            .mapNotNull { symbol ->
                val displayPath = symbol.source?.displayPath?.let(::normalize)
                    ?: return@mapNotNull null
                val matchingHunk = hunks.firstOrNull { hunk ->
                    hunk.pathCandidates().any { hunkPath -> pathsMatch(displayPath, normalize(hunkPath)) } &&
                        hunkTouchesSymbol(hunk, symbol)
                }
                val pathMatched = normalizedPaths.any { changedPath -> pathsMatch(displayPath, changedPath) }
                if (!pathMatched && matchingHunk == null) {
                    return@mapNotNull null
                }
                // 优先采用 hunk 提供的变更类型，否则回退到文件级别变更类型，最后兜底为 MODIFIED。
                val changeKind = matchingHunk?.changeKind
                    ?: files.firstOrNull { file ->
                        listOfNotNull(file.oldPath, file.newPath).any { path -> pathsMatch(displayPath, normalize(path)) }
                    }?.changeKind?.name
                    ?: "MODIFIED"
                ChangedSymbol(
                    symbolId = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    filePath = symbol.source?.displayPath,
                    startLine = symbol.source?.startLine,
                    endLine = symbol.source?.endLine,
                    hunk = matchingHunk,
                    changeKind = changeKind,
                    reason = if (matchingHunk != null) "HUNK_TOUCHES_SYMBOL_LINES" else "PATH_MATCHED_SYMBOL_FILE",
                )
            }
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)
    }

    /**
     * 判断两个路径是否可视为相同文件，支持相对路径前缀匹配。
     */
    private fun pathsMatch(displayPath: String, changedPath: String): Boolean =
        displayPath == changedPath ||
            displayPath.endsWith("/$changedPath") ||
            changedPath.endsWith("/$displayPath")

    /**
     * 判断 hunk 是否覆盖到符号源码行范围。
     *
     * 删除或新文件场景下使用旧行范围，其它情况优先使用新行范围。
     */
    private fun hunkTouchesSymbol(
        hunk: ChangedHunk,
        symbol: JvmSymbol,
    ): Boolean {
        val start = symbol.source?.startLine ?: return false
        val end = symbol.source?.endLine ?: start
        // 当 hunk 没有新行信息时使用旧行范围计算重叠，避免遗漏删除型 hunk。
        val useOldRange = hunk.newLineCount == 0 || hunk.newStartLine == null
        val hunkStart = if (useOldRange) {
            hunk.oldStartLine
        } else {
            hunk.newStartLine
        } ?: hunk.oldStartLine ?: return false
        val lineCount = if (useOldRange) {
            hunk.oldLineCount
        } else {
            hunk.newLineCount
        } ?: hunk.oldLineCount ?: 1
        val hunkEnd = hunkStart + lineCount.coerceAtLeast(1) - 1
        return hunkStart <= end && hunkEnd >= start
    }

    /**
     * 从 diff 文本中解析出代码块信息。
     *
     * 处理 diff header（rename from/to、--- 与 +++ 等），并把 `@@ ... @@` 解析为 [ChangedHunk]。
     */
    private fun parseUnifiedDiffHunks(text: String): List<ChangedHunk> {
        if (!text.contains("@@") || !text.contains("diff --git")) {
            return emptyList()
        }
        val hunks = mutableListOf<ChangedHunk>()
        var oldFile: String? = null
        var newFile: String? = null
        var renameFrom: String? = null
        var renameTo: String? = null
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> {
                    // 进入新文件 diff 段时重置当前段的文件路径状态。
                    oldFile = null
                    newFile = null
                    renameFrom = null
                    renameTo = null
                    val parts = line.removePrefix("diff --git ").split(' ').filter(String::isNotBlank)
                    oldFile = parts.getOrNull(0)?.toDiffPath()
                    newFile = parts.getOrNull(1)?.toDiffPath()
                }
                line.startsWith("rename from ") -> {
                    renameFrom = line.removePrefix("rename from ").trim().takeIf(String::isNotBlank)
                    oldFile = renameFrom
                }
                line.startsWith("rename to ") -> {
                    renameTo = line.removePrefix("rename to ").trim().takeIf(String::isNotBlank)
                    newFile = renameTo
                }
                line.startsWith("--- ") -> {
                    oldFile = line.removePrefix("--- ").toDiffPath()
                }
                line.startsWith("+++ ") -> {
                    newFile = line.removePrefix("+++ ").toDiffPath()
                }
                line.startsWith("@@") -> {
                    // 拼出当前 hunk 关联的文件路径，并按新旧路径判断 hunk 变更类型。
                    val filePath = newFile ?: oldFile ?: renameTo ?: renameFrom ?: return@forEach
                    parseHunkHeader(
                        header = line,
                        filePath = filePath,
                        oldFilePath = oldFile ?: renameFrom,
                        newFilePath = newFile ?: renameTo,
                        changeKind = hunkChangeKind(oldFile ?: renameFrom, newFile ?: renameTo),
                    )?.let(hunks::add)
                }
            }
        }
        return hunks
    }

    /**
     * 解析单个 hunk 头文本，输出 [ChangedHunk]。
     */
    private fun parseHunkHeader(
        header: String,
        filePath: String,
        oldFilePath: String?,
        newFilePath: String?,
        changeKind: String,
    ): ChangedHunk? {
        val match = HUNK_PATTERN.find(header) ?: return null
        return ChangedHunk(
            filePath = filePath,
            oldStartLine = match.groupValues.getOrNull(1)?.toIntOrNull(),
            oldLineCount = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 1,
            newStartLine = match.groupValues.getOrNull(3)?.toIntOrNull(),
            newLineCount = match.groupValues.getOrNull(4)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 1,
            header = header,
            oldFilePath = oldFilePath,
            newFilePath = newFilePath,
            changeKind = changeKind,
        )
    }

    /**
     * 返回代码块涉及的全部候选路径，便于路径匹配兜底。
     */
    private fun ChangedHunk.pathCandidates(): List<String> =
        listOfNotNull(filePath, oldFilePath, newFilePath).distinct()

    /**
     * 把 [GitHunk] 转换为统一的 [ChangedHunk]，并附带文件级变更类型。
     */
    private fun GitHunk.toChangedHunk(file: GitChangedFile): ChangedHunk =
        ChangedHunk(
            filePath = file.newPath ?: file.oldPath.orEmpty(),
            oldStartLine = oldStart,
            oldLineCount = oldLineCount,
            newStartLine = newStart,
            newLineCount = newLineCount,
            header = header,
            oldFilePath = file.oldPath,
            newFilePath = file.newPath,
            changeKind = when (file.changeKind) {
                GitChangeKind.ADDED -> "HUNK_ADDED"
                GitChangeKind.DELETED -> "HUNK_DELETED"
                GitChangeKind.RENAMED -> "HUNK_RENAMED"
                GitChangeKind.COPIED -> "HUNK_COPIED"
                GitChangeKind.MODIFIED -> "HUNK_MODIFIED"
            },
        )

    /**
     * 根据新旧文件路径推断 hunk 的变更类型。
     */
    private fun hunkChangeKind(oldFilePath: String?, newFilePath: String?): String =
        when {
            oldFilePath != null && newFilePath == null -> "HUNK_DELETED"
            oldFilePath == null && newFilePath != null -> "HUNK_ADDED"
            oldFilePath != null && newFilePath != null && normalize(oldFilePath) != normalize(newFilePath) -> "HUNK_RENAMED"
            else -> "HUNK_MODIFIED"
        }

    /**
     * 从 diff header 路径段（如 a/、b/、/dev/null）中还原真实路径。
     */
    private fun String.toDiffPath(): String? =
        trim()
            .removePrefix("a/")
            .removePrefix("b/")
            .takeUnless { it == "/dev/null" }
            ?.takeIf(String::isNotBlank)

    /**
     * 从一段文本中提取可能的源文件路径候选。
     */
    private fun pathCandidates(text: String): List<String> =
        GraphDiffChangedFileExtractor.pathCandidates(text).ifEmpty { listOf(text) }

    private companion object {
        /** 匹配 unified diff 中的 `@@ -start,count +start,count @@` 行。 */
        private val HUNK_PATTERN = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    }

    /**
     * 标准化路径，去除前导 `./` 并统一分隔符，便于跨表示比对。
     */
    private fun normalize(path: String): String =
        runCatching { Path.of(path).normalize().toString().replace('\\', '/') }
            .getOrDefault(path.replace('\\', '/'))
            .removePrefix("./")
}

/**
 * 从图模型 diff 中抽取涉及的源文件路径集合。
 */
object GraphDiffChangedFileExtractor {
    /**
     * 从 diff 条目中提取所有可能涉及的源文件路径。
     */
    fun changedFiles(diff: com.charmnight.linkgraph.model.GraphDiff): List<String> =
        diff.entries
            .flatMap { entry ->
                // 把可能包含路径的字段都纳入候选扫描范围。
                listOfNotNull(
                    entry.message,
                    entry.counterpartId,
                    entry.elementId,
                ) + entry.fields
            }
            .flatMap(::pathCandidates)
            .distinct()
            .sorted()

    /**
     * 从单段文本中提取看起来像源文件路径的候选。
     */
    fun pathCandidates(text: String?): List<String> {
        val source = text ?: return emptyList()
        return PATH_PATTERN.findAll(source)
            .map { match -> match.value.trim('\'', '"', '`', ',', ';', ')', '(') }
            .filter { value -> value.contains('/') || value.contains('\\') }
            .filter { value -> value.substringAfterLast('.').length in 1..8 }
            .toList()
    }

    /** 匹配常见源文件扩展名，用于从文本中提取候选路径。 */
    private val PATH_PATTERN = Regex("""[A-Za-z0-9_./\\:-]+\.(java|kt|kts|xml|yml|yaml|properties|sql|md)""")
}

/**
 * 暴露给上层的 Git diff 获取入口，封装变更集 provider。
 */
class GitDiffProvider(
    /** 保存项目根路径，用于定位 Git 仓库。 */
    private val projectBasePath: String?,
) {
    /** 保存底层变更集获取器，统一封装 staged/工作区/unified diff。 */
    private val changeSetProvider = GitChangeSetProvider(projectBasePath)

    /**
     * 获取包含 staged 与工作区改动的 unified diff 文本。
     */
    fun unifiedDiff(selectedPaths: List<String> = emptyList()): String? {
        return changeSetProvider.unifiedDiff(selectedPaths)
    }
}

/**
 * 代码审查的核心查询服务。
 *
 * 在架构图索引基础上提供变更符号识别、爆炸半径计算、相关测试关联以及证据包构造等能力，
 * 供上层（UI、LLM 上下文等）统一调用。
 */
class ReviewGraphQueryService(
    /** 保存架构图索引。 */
    private val index: ArchitectureGraphIndex,
    /** 保存证据包构造器。 */
    private val evidenceBundleBuilder: ReviewEvidenceBundleBuilder = ReviewEvidenceBundleBuilder(),
    /** 保存可选的源码内容解析器，用于读取代码片段。 */
    private val sourceResolver: SourceContentResolver? = null,
    /** 保存可选的基线符号映射器，用于处理已删除/重命名文件的旧符号。 */
    private val baselineSymbolMapper: GitBaselineSymbolMapper? = null,
) {
    /** 保存架构图查询服务，封装上下游遍历。 */
    private val architectureQuery = ArchitectureGraphQueryService(index)
    /** 保存 diff 与符号之间的映射器。 */
    private val mapper = GitDiffSymbolMapper(index)

    /**
     * 根据路径或 diff 文本返回变更符号列表。
     */
    fun changedSymbols(changedFiles: List<String>): List<ChangedSymbol> =
        mapper.mapChangedFiles(changedFiles)

    /**
     * 根据结构化变更集返回变更符号列表，并合并基线版符号。
     */
    fun changedSymbolsForChangeSet(changeSet: List<GitChangedFile>): List<ChangedSymbol> =
        (mapper.mapChangeSet(changeSet) + baselineSymbolMapper?.mapBaselineOnlySymbols(changeSet).orEmpty())
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)

    /**
     * 计算给定路径或 diff 文本的爆炸半径。
     */
    fun blastRadius(
        changedFiles: List<String>,
        depth: Int = 2,
    ): BlastRadius {
        return blastRadiusForChangedSymbols(changedSymbols(changedFiles), depth)
    }

    /**
     * 计算结构化变更集的爆炸半径。
     */
    fun blastRadiusForChangeSet(
        changeSet: List<GitChangedFile>,
        depth: Int = 2,
    ): BlastRadius =
        blastRadiusForChangedSymbols(changedSymbolsForChangeSet(changeSet), depth)

    /**
     * 在变更符号列表上计算上下游、相关测试、受影响包/模块等爆炸半径信息。
     */
    private fun blastRadiusForChangedSymbols(
        changed: List<ChangedSymbol>,
        depth: Int,
    ): BlastRadius {
        // 只对能在当前索引中找到的、非基线符号做依赖计算，基线符号仅保留展示信息。
        val indexedChanged = changed.filter { symbol -> !symbol.baselineOnly && index.findSymbol(symbol.symbolId) != null }
        val upstreamByChangedSymbolId = indexedChanged.associate { symbol ->
            symbol.symbolId to architectureQuery.upstream(symbol.symbolId, depth)
                .distinctBy(JvmSymbol::id)
                .sortedBy(JvmSymbol::qualifiedName)
        }
        val downstreamByChangedSymbolId = indexedChanged.associate { symbol ->
            symbol.symbolId to architectureQuery.downstream(symbol.symbolId, depth)
                .distinctBy(JvmSymbol::id)
                .sortedBy(JvmSymbol::qualifiedName)
        }
        val upstream = upstreamByChangedSymbolId.values.flatten()
            .distinctBy(JvmSymbol::id)
            .sortedBy(JvmSymbol::qualifiedName)
        val downstream = downstreamByChangedSymbolId.values.flatten()
            .distinctBy(JvmSymbol::id)
            .sortedBy(JvmSymbol::qualifiedName)
        val changedIds = indexedChanged.mapTo(linkedSetOf(), ChangedSymbol::symbolId)
        // 变更符号若是类，则把它内部的方法/字段也展开，避免遗漏成员级关系。
        val expandedChangedIdsByChangedSymbolId = indexedChanged.associate { symbol ->
            symbol.symbolId to (listOf(symbol.symbolId) + memberSymbolIds(symbol.symbolId)).toSet()
        }
        val expandedChangedIds = expandedChangedIdsByChangedSymbolId.values.flatten().toSet()
        val relatedRelations = expandedChangedIds.flatMap { symbolId ->
            index.relationIndex.outgoing(symbolId) + index.relationIndex.incoming(symbolId)
        }.distinctBy(JvmRelation::id)
        val affectedClassNames = indexedChanged.mapNotNull(::ownerClassForChangedSymbol)
        val testRelations = relatedRelations.filter(::isTestRelation)
        val relatedTestPairsByChangedSymbolId = expandedChangedIdsByChangedSymbolId.mapValues { (_, ids) ->
            testRelations
                .flatMap { relation -> relatedTestSymbols(relation, ids) }
                .distinctBy { (symbol, _) -> symbol.id }
                .sortedBy { (symbol, _) -> symbol.qualifiedName }
        }
        val relatedTestPairs = relatedTestPairsByChangedSymbolId.values.flatten()
            .distinctBy { (symbol, _) -> symbol.id }
            .sortedBy { (symbol, _) -> symbol.qualifiedName }
        val relatedTests = relatedTestPairs.map { (symbol, _) -> symbol }
        val affectedClasses = affectedClassNames.mapNotNull(index::findClass).distinctBy(JvmClassSymbol::id)
        return BlastRadius(
            changedSymbols = changed,
            upstream = upstream,
            downstream = downstream,
            upstreamByChangedSymbolId = upstreamByChangedSymbolId,
            downstreamByChangedSymbolId = downstreamByChangedSymbolId,
            relatedTestsByChangedSymbolId = relatedTestPairsByChangedSymbolId.mapValues { (_, pairs) ->
                pairs.map { (symbol, _) -> symbol }
            },
            relatedTestReasonsByChangedSymbolId = relatedTestPairsByChangedSymbolId.mapValues { (_, pairs) ->
                pairs.associate { (symbol, reason) -> symbol.id to reason }
            },
            spiProviders = relatedRelations.filter { relation -> relation.kind == JvmRelationKind.SPI_PROVIDES },
            reflectionTargets = relatedRelations.filter { relation -> relation.kind == JvmRelationKind.REFLECTS_TO },
            serviceLoaderLoads = relatedRelations.filter { relation -> relation.kind == JvmRelationKind.SERVICE_LOADER_LOADS },
            proxyTargets = relatedRelations.filter { relation -> relation.kind == JvmRelationKind.USES_PROXY },
            testRelations = testRelations,
            relatedTests = relatedTests,
            relatedTestReasons = relatedTestPairs.associate { (symbol, reason) -> symbol.id to reason },
            affectedPackages = affectedClasses.map(JvmClassSymbol::packageName).filter(String::isNotBlank).distinct().sorted(),
            affectedModules = affectedClasses.mapNotNull(JvmClassSymbol::moduleName).distinct().sorted(),
        )
    }

    /**
     * 返回某类符号下的全部成员（方法与字段）标识，用于展开爆炸半径查询范围。
     */
    private fun memberSymbolIds(symbolId: String): List<String> {
        val symbol = index.findSymbol(symbolId) ?: return emptyList()
        val className = when (symbol) {
            is JvmClassSymbol -> symbol.qualifiedName
            else -> return emptyList()
        }
        return index.symbolIndex.methodsBySignature.values
            .filter { method -> method.ownerClassName == className }
            .map { method -> method.id } +
            index.symbolIndex.fieldsByQualifiedName.values
                .filter { field -> field.ownerClassName == className }
                .map { field -> field.id }
    }

    /**
     * 找出变更符号的所属类全限定名，用于聚合受影响的包/模块。
     */
    private fun ownerClassForChangedSymbol(symbol: ChangedSymbol): String? {
        val jvmSymbol = index.findSymbol(symbol.symbolId) ?: return null
        return when (jvmSymbol) {
            is JvmClassSymbol -> jvmSymbol.qualifiedName
            is JvmMethodSymbol -> jvmSymbol.ownerClassName
            is JvmFieldSymbol -> jvmSymbol.ownerClassName
            else -> null
        }
    }

    /**
     * 判断一条关系是否属于测试关系。
     *
     * 显式测试关系或调用关系携带测试框架标记时都视为测试关系。
     */
    private fun isTestRelation(relation: JvmRelation): Boolean =
        relation.kind == JvmRelationKind.TESTS ||
            (relation.kind == JvmRelationKind.CALLS && relation.metadata["test.framework"] == "true")

    /**
     * 从一条关系中找出与变更符号相关的测试符号对，附上关联原因。
     */
    private fun relatedTestSymbols(
        relation: JvmRelation,
        changedIds: Set<String>,
    ): List<Pair<JvmSymbol, String>> {
        // 关系两端都不在变更范围时跳过，避免无关关系污染结果。
        if (relation.toSymbolId !in changedIds && relation.fromSymbolId !in changedIds) {
            return emptyList()
        }
        if (relation.kind == JvmRelationKind.TESTS && relation.toSymbolId in changedIds) {
            val symbol = index.findSymbol(relation.fromSymbolId) ?: return emptyList()
            return listOf(symbol to (relation.metadata["test.reason"] ?: "CALL_PATH"))
        }
        if (relation.kind == JvmRelationKind.CALLS && relation.toSymbolId in changedIds) {
            // 优先使用关系元数据中保存的源方法签名查找测试方法；找不到时再退回到 from 符号本身。
            val sourceMethods = relation.metadata["call.sourceMethodSignatures"]
                .orEmpty()
                .split(';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .mapNotNull(index::findMethod)
                .filter(::isTestSymbol)
            if (sourceMethods.isNotEmpty()) {
                return sourceMethods.map { method -> method to "CALL_PATH" }
            }
            val source = index.findSymbol(relation.fromSymbolId)?.takeIf(::isTestSymbol)
                ?: return emptyList()
            return listOf(source to "CALL_PATH")
        }
        return emptyList()
    }

    /**
     * 判断给定符号是否属于测试源码。
     */
    private fun isTestSymbol(symbol: JvmSymbol): Boolean {
        val ownerClass = ownerClassForSymbol(symbol) ?: return false
        return ownerClass.testSource
    }

    /**
     * 返回符号的所属类符号，方法/字段时回查 ownerClass。
     */
    private fun ownerClassForSymbol(symbol: JvmSymbol): JvmClassSymbol? =
        when (symbol) {
            is JvmClassSymbol -> symbol
            is JvmMethodSymbol -> index.findClass(symbol.ownerClassName)
            is JvmFieldSymbol -> index.findClass(symbol.ownerClassName)
            else -> null
        }

    /**
     * 基于路径或 diff 文本构造证据包。
     */
    fun buildEvidenceBundle(
        changedFiles: List<String>,
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        val radius = blastRadius(changedFiles, depth)
        return evidenceBundleBuilder.build(radius, sourceResolver)
    }

    /**
     * 基于结构化变更集构造证据包。
     */
    fun buildEvidenceBundleForChangeSet(
        changeSet: List<GitChangedFile>,
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        val radius = blastRadiusForChangeSet(changeSet, depth)
        return evidenceBundleBuilder.build(radius, sourceResolver, changeSet)
    }

    /**
     * 基于图模型 diff 构造证据包，可只针对选中的 diff 条目。
     */
    fun buildEvidenceBundleForDiff(
        diff: com.charmnight.linkgraph.model.GraphDiff,
        selectedDiffItemIds: List<String> = emptyList(),
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        // 优先使用用户选中的 diff 条目；未选中时回退到全部条目。
        val selectedEntries = diff.entries.filter { entry -> entry.elementId in selectedDiffItemIds }
            .takeIf(List<*>::isNotEmpty)
            ?: diff.entries
        val changedFiles = GraphDiffChangedFileExtractor.changedFiles(diff.copy(entries = selectedEntries))
        return buildEvidenceBundle(changedFiles, depth)
    }
}

/**
 * 把 [BlastRadius] 中的符号、关系等聚合为扁平证据包，供 LLM 上下文或 UI 消费。
 */
class ReviewEvidenceBundleBuilder {
    /**
     * 构造证据包，包括变更符号、关键关系、上下游与相关测试。
     */
    fun build(
        radius: BlastRadius,
        sourceResolver: SourceContentResolver? = null,
        gitChangedFiles: List<GitChangedFile> = emptyList(),
    ): ReviewEvidenceBundle {
        val changedEvidence: List<ReviewEvidenceRef> = radius.changedSymbols.map { symbol ->
            val (snippet, snippetUnavailable) = snippetPayload(
                resolver = sourceResolver,
                filePath = symbol.filePath,
                virtualFileUrl = null,
                startLine = symbol.hunk?.newStartLine ?: symbol.startLine,
                endLine = symbol.hunk?.newStartLine?.let { start ->
                    start + (symbol.hunk.newLineCount ?: 1).coerceAtLeast(1) - 1
                } ?: symbol.endLine,
            )
            ReviewChangedSymbolEvidenceRef(
                symbolId = symbol.symbolId,
                qualifiedName = symbol.qualifiedName,
                filePath = symbol.filePath,
                startLine = symbol.startLine,
                endLine = symbol.endLine,
                changeKind = symbol.changeKind,
                baselineOnly = symbol.baselineOnly,
                blastRadiusIncomplete = symbol.blastRadiusIncomplete,
                unavailableReason = symbol.unavailableReason,
                hunkHeader = symbol.hunk?.header,
                hunkNewStartLine = symbol.hunk?.newStartLine,
                hunkNewLineCount = symbol.hunk?.newLineCount,
                snippet = snippet,
                snippetUnavailable = snippetUnavailable,
            )
        }
        // 关键关系（SPI/反射/ServiceLoader/代理/测试）每条 sample 都生成一条证据项，便于展示具体出处。
        val relationEvidence: List<ReviewEvidenceRef> = (radius.spiProviders + radius.reflectionTargets + radius.serviceLoaderLoads + radius.proxyTargets + radius.testRelations)
            .flatMap { relation ->
                relation.samples.map { sample ->
                    val (snippet, snippetUnavailable) = snippetPayload(
                        resolver = sourceResolver,
                        filePath = sample.filePath,
                        virtualFileUrl = sample.virtualFileUrl,
                        startLine = sample.startLine,
                        endLine = sample.endLine,
                    )
                    ReviewRelationEvidenceRef(
                        relationId = relation.id,
                        kind = relation.kind.name,
                        confidence = relation.confidence.name,
                        filePath = sample.filePath,
                        virtualFileUrl = sample.virtualFileUrl,
                        startLine = sample.startLine,
                        endLine = sample.endLine,
                        decompiled = sample.decompiled,
                        claim = sample.claim,
                        snippet = snippet,
                        snippetUnavailable = snippetUnavailable,
                    )
                }
            }
        // 上下游与相关测试统一截断到 50 条，避免证据包过大。
        val symbolEvidence: List<ReviewEvidenceRef> = (radius.upstream + radius.downstream + radius.relatedTests)
            .take(50)
            .map { symbol ->
                val (snippet, snippetUnavailable) = snippetPayload(
                    resolver = sourceResolver,
                    filePath = symbol.source?.displayPath,
                    virtualFileUrl = symbol.source?.virtualFileUrl,
                    startLine = symbol.source?.startLine,
                    endLine = symbol.source?.endLine,
                )
                ReviewSymbolEvidenceRef(
                    symbolId = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    filePath = symbol.source?.displayPath,
                    virtualFileUrl = symbol.source?.virtualFileUrl,
                    decompiled = symbol.source?.decompiled ?: false,
                    origin = symbol.origin.name,
                    snippet = snippet,
                    snippetUnavailable = snippetUnavailable,
                )
            }
        return ReviewEvidenceBundle(
            changedSymbols = radius.changedSymbols,
            blastRadius = radius,
            evidenceRefs = changedEvidence + relationEvidence + symbolEvidence,
            gitChangedFiles = gitChangedFiles,
        )
    }

    /**
     * 读取给定源码位置的代码片段并以 DTO 形式返回。
     *
     * 返回 Pair<可用片段或 null, 不可用原因或 null>，二者互斥。
     */
    private fun snippetPayload(
        resolver: SourceContentResolver?,
        filePath: String?,
        virtualFileUrl: String?,
        startLine: Int?,
        endLine: Int?,
    ): Pair<ReviewSnippetPayload?, ReviewSnippetUnavailable?> {
        if (resolver == null) return null to ReviewSnippetUnavailable("SOURCE_RESOLVER_UNAVAILABLE")
        val path = virtualFileUrl ?: filePath ?: return null to ReviewSnippetUnavailable("SOURCE_PATH_UNAVAILABLE")
        // 优先按精确区间读取，区间不可用时回退到读取整个文件。
        val content = resolver.readSnippetByPath(path, startLine, endLine)
            ?: resolver.readByPath(path)
            ?: return null to ReviewSnippetUnavailable("SOURCE_SNIPPET_UNAVAILABLE")
        return ReviewSnippetPayload(
            snippet = content.text.take(2_000),
            snippetOrigin = content.origin.name,
            snippetVirtualFileUrl = content.virtualFileUrl,
            snippetStartLine = content.startLine,
            snippetEndLine = content.endLine,
            snippetDecompiled = content.decompiled,
        ) to null
    }
}
