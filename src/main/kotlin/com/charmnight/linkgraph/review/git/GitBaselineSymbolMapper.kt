package com.charmnight.linkgraph.review.git

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.review.ChangedHunk
import com.charmnight.linkgraph.review.ChangedSymbol

/**
 * 把已删除或重命名的 JVM 源文件中存在的符号映射为基线版变更符号。
 *
 * 这些符号在当前代码中已经不存在，因此需要从 HEAD 版本的源码文本中提取，
 * 用于在审查视图里展示“被删掉的符号”这一类信息。
 */
class GitBaselineSymbolMapper(
    /** 保存 Git 变更集获取器，用于读取 HEAD 版本文件内容。 */
    private val changeSetProvider: GitChangeSetProvider,
    /** 保存基于源码文本的 JVM 符号提取器。 */
    private val symbolExtractor: JvmSourceTextSymbolExtractor,
) {
    /**
     * 把变更文件列表中删除/重命名/带删除行的文件映射为基线版符号列表。
     */
    fun mapBaselineOnlySymbols(files: List<GitChangedFile>): List<ChangedSymbol> {
        return files
            .filter(::needsBaselineMapping)
            .flatMap { file ->
                val oldPath = file.oldPath ?: return@flatMap emptyList()
                // 读不到 HEAD 版本时记录为不可用证据，便于上层告知用户原因。
                val text = changeSetProvider.readHeadFile(oldPath)
                    ?: return@flatMap unavailableFileEvidence(file, oldPath, "BASELINE_SOURCE_UNAVAILABLE")
                val mapped = runCatching { extractSymbols(oldPath, text, file) }
                    .getOrElse {
                        return@flatMap unavailableFileEvidence(file, oldPath, "BASELINE_PSI_SYMBOL_MAPPING_FAILED")
                    }
                mapped.ifEmpty { unavailableFileEvidence(file, oldPath, "BASELINE_SYMBOL_UNAVAILABLE") }
            }
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)
    }

    /**
     * 从 HEAD 版本文件文本中提取 JVM 符号并转换为变更符号。
     */
    private fun extractSymbols(
        oldPath: String,
        text: String,
        file: GitChangedFile,
    ): List<ChangedSymbol> {
        val lines = text.lines()
        return symbolExtractor.extract(oldPath, text)
            .mapNotNull { symbol -> symbol.toChangedSymbol(oldPath, lines.size, file) }
    }

    /**
     * 把提取出的 JVM 源码符号转换为基线版变更符号。
     *
     * 删除或重命名场景下保留全部类符号；其他场景需命中带删除行的代码块才视为变更。
     */
    private fun JvmSourceTextSymbol.toChangedSymbol(
        oldPath: String,
        lineCount: Int,
        file: GitChangedFile,
    ): ChangedSymbol? {
        val includeClasses = file.changeKind == GitChangeKind.DELETED || file.changeKind == GitChangeKind.RENAMED
        if (!includeClasses && !touchesOldHunk(file, startLine, endLine)) {
            return null
        }
        return ChangedSymbol(
            symbolId = "baseline:${stableJvmId(kind, qualifiedName)}",
            qualifiedName = qualifiedName,
            filePath = oldPath,
            startLine = startLine,
            // 把结束行号限制在文件实际行数范围内，避免越界。
            endLine = endLine.coerceAtLeast(startLine).coerceAtMost(lineCount.coerceAtLeast(startLine)),
            // 优先取命中符号的代码块；没有命中时回退到文件第一个代码块，便于展示文件级变更。
            hunk = file.hunks.firstOrNull { hunk -> hunk.touches(startLine, endLine) }?.toChangedHunk(file)
                ?: file.hunks.firstOrNull()?.toChangedHunk(file),
            changeKind = file.changeKind.name,
            baselineOnly = true,
            blastRadiusIncomplete = true,
            unavailableReason = null,
            reason = "BASELINE_DELETED_SYMBOL",
        )
    }

    /**
     * 判断文件是否需要做基线符号映射。
     *
     * 仅对 JVM 源文件、且属于删除/重命名/包含删除行的情况做基线处理。
     */
    private fun needsBaselineMapping(file: GitChangedFile): Boolean =
        file.oldPath?.isJvmSourcePath() == true &&
            (
                file.changeKind == GitChangeKind.DELETED ||
                    file.changeKind == GitChangeKind.RENAMED ||
                    file.hunks.any { hunk -> hunk.hasRemovedLines() }
                )

    /**
     * 判断路径是否为 JVM 源码文件（Java/Kotlin/Kotlin 脚本）。
     */
    private fun String.isJvmSourcePath(): Boolean =
        lowercase().let { path -> path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".kts") }

    /**
     * 判断符号所在行是否命中文件中带删除行的代码块。
     */
    private fun touchesOldHunk(
        file: GitChangedFile,
        startLine: Int,
        endLine: Int,
    ): Boolean =
        file.hunks.isEmpty() || file.hunks.any { hunk -> hunk.touches(startLine, endLine) && hunk.hasRemovedLines() }

    /**
     * 判断当前代码块在旧行范围上是否覆盖到给定符号的行范围。
     */
    private fun GitHunk.touches(
        startLine: Int,
        endLine: Int,
    ): Boolean {
        val hunkStart = oldStart ?: return false
        val hunkEnd = hunkStart + (oldLineCount ?: 1).coerceAtLeast(1) - 1
        return hunkStart <= endLine && hunkEnd >= startLine
    }

    /**
     * 判断代码块是否包含被删除的行。
     *
     * 同时识别显式删除行和“新文件行数为 0 但旧行数非 0”的整块删除场景。
     */
    private fun GitHunk.hasRemovedLines(): Boolean =
        lines.any { line -> line.startsWith("-") && !line.startsWith("---") } ||
            (newLineCount == 0 && (oldLineCount ?: 0) > 0)

    /**
     * 当文件无法提取到基线符号时，构造一条不可用证据占位，便于上层明确失败原因。
     */
    private fun unavailableFileEvidence(
        file: GitChangedFile,
        oldPath: String,
        reason: String,
    ): List<ChangedSymbol> =
        listOf(
            ChangedSymbol(
                symbolId = "baseline:unavailable:${stableJvmId("file", oldPath)}",
                qualifiedName = oldPath,
                filePath = oldPath,
                startLine = null,
                endLine = null,
                hunk = file.hunks.firstOrNull()?.toChangedHunk(file),
                changeKind = file.changeKind.name,
                baselineOnly = true,
                blastRadiusIncomplete = true,
                unavailableReason = reason,
                reason = "BASELINE_DELETED_SYMBOL",
            ),
        )

    /**
     * 把 [GitHunk] 转换为统一的 [ChangedHunk]，附上文件级变更类型前缀。
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
            changeKind = "HUNK_${file.changeKind.name}",
        )
}
