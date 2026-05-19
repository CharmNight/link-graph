package com.charmnight.linkgraph.review.git

import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.review.ChangedHunk
import com.charmnight.linkgraph.review.ChangedSymbol

class GitBaselineSymbolMapper(
    private val changeSetProvider: GitChangeSetProvider,
    private val symbolExtractor: JvmSourceTextSymbolExtractor,
) {
    fun mapBaselineOnlySymbols(files: List<GitChangedFile>): List<ChangedSymbol> {
        return files
            .filter(::needsBaselineMapping)
            .flatMap { file ->
                val oldPath = file.oldPath ?: return@flatMap emptyList()
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

    private fun extractSymbols(
        oldPath: String,
        text: String,
        file: GitChangedFile,
    ): List<ChangedSymbol> {
        val lines = text.lines()
        return symbolExtractor.extract(oldPath, text)
            .mapNotNull { symbol -> symbol.toChangedSymbol(oldPath, lines.size, file) }
    }

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
            endLine = endLine.coerceAtLeast(startLine).coerceAtMost(lineCount.coerceAtLeast(startLine)),
            hunk = file.hunks.firstOrNull { hunk -> hunk.touches(startLine, endLine) }?.toChangedHunk(file)
                ?: file.hunks.firstOrNull()?.toChangedHunk(file),
            changeKind = file.changeKind.name,
            baselineOnly = true,
            blastRadiusIncomplete = true,
            unavailableReason = null,
        )
    }

    private fun needsBaselineMapping(file: GitChangedFile): Boolean =
        file.oldPath?.isJvmSourcePath() == true &&
            (
                file.changeKind == GitChangeKind.DELETED ||
                    file.changeKind == GitChangeKind.RENAMED ||
                    file.hunks.any { hunk -> hunk.hasRemovedLines() }
                )

    private fun String.isJvmSourcePath(): Boolean =
        lowercase().let { path -> path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".kts") }

    private fun touchesOldHunk(
        file: GitChangedFile,
        startLine: Int,
        endLine: Int,
    ): Boolean =
        file.hunks.isEmpty() || file.hunks.any { hunk -> hunk.touches(startLine, endLine) && hunk.hasRemovedLines() }

    private fun GitHunk.touches(
        startLine: Int,
        endLine: Int,
    ): Boolean {
        val hunkStart = oldStart ?: return false
        val hunkEnd = hunkStart + (oldLineCount ?: 1).coerceAtLeast(1) - 1
        return hunkStart <= endLine && hunkEnd >= startLine
    }

    private fun GitHunk.hasRemovedLines(): Boolean =
        lines.any { line -> line.startsWith("-") && !line.startsWith("---") } ||
            (newLineCount == 0 && (oldLineCount ?: 0) > 0)

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
            ),
        )

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
