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

data class ChangedHunk(
    val filePath: String,
    val oldStartLine: Int?,
    val oldLineCount: Int?,
    val newStartLine: Int?,
    val newLineCount: Int?,
    val header: String,
    val oldFilePath: String? = null,
    val newFilePath: String? = filePath,
    val changeKind: String = "HUNK_MODIFIED",
)

data class ChangedSymbol(
    val symbolId: String,
    val qualifiedName: String,
    val filePath: String?,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val hunk: ChangedHunk? = null,
    val changeKind: String = "MODIFIED",
    val baselineOnly: Boolean = false,
    val blastRadiusIncomplete: Boolean = false,
    val unavailableReason: String? = null,
)

data class BlastRadius(
    val changedSymbols: List<ChangedSymbol>,
    val upstream: List<JvmSymbol>,
    val downstream: List<JvmSymbol>,
    val upstreamByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    val downstreamByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    val relatedTestsByChangedSymbolId: Map<String, List<JvmSymbol>> = emptyMap(),
    val relatedTestReasonsByChangedSymbolId: Map<String, Map<String, String>> = emptyMap(),
    val spiProviders: List<JvmRelation>,
    val reflectionTargets: List<JvmRelation>,
    val serviceLoaderLoads: List<JvmRelation>,
    val proxyTargets: List<JvmRelation>,
    val testRelations: List<JvmRelation> = emptyList(),
    val relatedTests: List<JvmSymbol>,
    val relatedTestReasons: Map<String, String> = emptyMap(),
    val affectedPackages: List<String> = emptyList(),
    val affectedModules: List<String> = emptyList(),
)

data class ReviewEvidenceBundle(
    val changedSymbols: List<ChangedSymbol>,
    val blastRadius: BlastRadius,
    val evidenceRefs: List<Map<String, Any?>>,
    val gitChangedFiles: List<GitChangedFile> = emptyList(),
)

class GitDiffSymbolMapper(
    private val index: ArchitectureGraphIndex,
) {
    fun mapChangedFiles(paths: List<String>): List<ChangedSymbol> {
        val changeSet = paths
            .filter { text -> text.contains("diff --git") }
            .flatMap(GitChangeSetProvider::parseUnifiedDiff)
        if (changeSet.isNotEmpty()) {
            return mapChangeSet(changeSet)
        }
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
                )
            }
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)
    }

    fun mapChangeSet(files: List<GitChangedFile>): List<ChangedSymbol> {
        if (files.isEmpty()) {
            return emptyList()
        }
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
                )
            }
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)
    }

    private fun pathsMatch(displayPath: String, changedPath: String): Boolean =
        displayPath == changedPath ||
            displayPath.endsWith("/$changedPath") ||
            changedPath.endsWith("/$displayPath")

    private fun hunkTouchesSymbol(
        hunk: ChangedHunk,
        symbol: JvmSymbol,
    ): Boolean {
        val start = symbol.source?.startLine ?: return false
        val end = symbol.source?.endLine ?: start
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

    private fun ChangedHunk.pathCandidates(): List<String> =
        listOfNotNull(filePath, oldFilePath, newFilePath).distinct()

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

    private fun hunkChangeKind(oldFilePath: String?, newFilePath: String?): String =
        when {
            oldFilePath != null && newFilePath == null -> "HUNK_DELETED"
            oldFilePath == null && newFilePath != null -> "HUNK_ADDED"
            oldFilePath != null && newFilePath != null && normalize(oldFilePath) != normalize(newFilePath) -> "HUNK_RENAMED"
            else -> "HUNK_MODIFIED"
        }

    private fun String.toDiffPath(): String? =
        trim()
            .removePrefix("a/")
            .removePrefix("b/")
            .takeUnless { it == "/dev/null" }
            ?.takeIf(String::isNotBlank)

    private fun pathCandidates(text: String): List<String> =
        GraphDiffChangedFileExtractor.pathCandidates(text).ifEmpty { listOf(text) }

    private companion object {
        private val HUNK_PATTERN = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    }

    private fun normalize(path: String): String =
        runCatching { Path.of(path).normalize().toString().replace('\\', '/') }
            .getOrDefault(path.replace('\\', '/'))
            .removePrefix("./")
}

object GraphDiffChangedFileExtractor {
    fun changedFiles(diff: com.charmnight.linkgraph.model.GraphDiff): List<String> =
        diff.entries
            .flatMap { entry ->
                listOfNotNull(
                    entry.message,
                    entry.counterpartId,
                    entry.elementId,
                ) + entry.fields
            }
            .flatMap(::pathCandidates)
            .distinct()
            .sorted()

    fun pathCandidates(text: String?): List<String> {
        val source = text ?: return emptyList()
        return PATH_PATTERN.findAll(source)
            .map { match -> match.value.trim('\'', '"', '`', ',', ';', ')', '(') }
            .filter { value -> value.contains('/') || value.contains('\\') }
            .filter { value -> value.substringAfterLast('.').length in 1..8 }
            .toList()
    }

    private val PATH_PATTERN = Regex("""[A-Za-z0-9_./\\:-]+\.(java|kt|kts|xml|yml|yaml|properties|sql|md)""")
}

class GitDiffProvider(
    private val projectBasePath: String?,
) {
    private val changeSetProvider = GitChangeSetProvider(projectBasePath)

    fun unifiedDiff(selectedPaths: List<String> = emptyList()): String? {
        return changeSetProvider.unifiedDiff(selectedPaths)
    }
}

class ReviewGraphQueryService(
    private val index: ArchitectureGraphIndex,
    private val evidenceBundleBuilder: ReviewEvidenceBundleBuilder = ReviewEvidenceBundleBuilder(),
    private val sourceResolver: SourceContentResolver? = null,
    private val baselineSymbolMapper: GitBaselineSymbolMapper? = null,
) {
    private val architectureQuery = ArchitectureGraphQueryService(index)
    private val mapper = GitDiffSymbolMapper(index)

    fun changedSymbols(changedFiles: List<String>): List<ChangedSymbol> =
        mapper.mapChangedFiles(changedFiles)

    fun changedSymbolsForChangeSet(changeSet: List<GitChangedFile>): List<ChangedSymbol> =
        (mapper.mapChangeSet(changeSet) + baselineSymbolMapper?.mapBaselineOnlySymbols(changeSet).orEmpty())
            .distinctBy(ChangedSymbol::symbolId)
            .sortedBy(ChangedSymbol::qualifiedName)

    fun blastRadius(
        changedFiles: List<String>,
        depth: Int = 2,
    ): BlastRadius {
        return blastRadiusForChangedSymbols(changedSymbols(changedFiles), depth)
    }

    fun blastRadiusForChangeSet(
        changeSet: List<GitChangedFile>,
        depth: Int = 2,
    ): BlastRadius =
        blastRadiusForChangedSymbols(changedSymbolsForChangeSet(changeSet), depth)

    private fun blastRadiusForChangedSymbols(
        changed: List<ChangedSymbol>,
        depth: Int,
    ): BlastRadius {
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

    private fun ownerClassForChangedSymbol(symbol: ChangedSymbol): String? {
        val jvmSymbol = index.findSymbol(symbol.symbolId) ?: return null
        return when (jvmSymbol) {
            is JvmClassSymbol -> jvmSymbol.qualifiedName
            is JvmMethodSymbol -> jvmSymbol.ownerClassName
            is JvmFieldSymbol -> jvmSymbol.ownerClassName
            else -> null
        }
    }

    private fun isTestRelation(relation: JvmRelation): Boolean =
        relation.kind == JvmRelationKind.TESTS ||
            (relation.kind == JvmRelationKind.CALLS && relation.metadata["test.framework"] == "true")

    private fun relatedTestSymbols(
        relation: JvmRelation,
        changedIds: Set<String>,
    ): List<Pair<JvmSymbol, String>> {
        if (relation.toSymbolId !in changedIds && relation.fromSymbolId !in changedIds) {
            return emptyList()
        }
        if (relation.kind == JvmRelationKind.TESTS && relation.toSymbolId in changedIds) {
            val symbol = index.findSymbol(relation.fromSymbolId) ?: return emptyList()
            return listOf(symbol to (relation.metadata["test.reason"] ?: "CALL_PATH"))
        }
        if (relation.kind == JvmRelationKind.CALLS && relation.toSymbolId in changedIds) {
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

    private fun isTestSymbol(symbol: JvmSymbol): Boolean {
        val ownerClass = ownerClassForSymbol(symbol) ?: return false
        return ownerClass.testSource
    }

    private fun ownerClassForSymbol(symbol: JvmSymbol): JvmClassSymbol? =
        when (symbol) {
            is JvmClassSymbol -> symbol
            is JvmMethodSymbol -> index.findClass(symbol.ownerClassName)
            is JvmFieldSymbol -> index.findClass(symbol.ownerClassName)
            else -> null
        }

    fun buildEvidenceBundle(
        changedFiles: List<String>,
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        val radius = blastRadius(changedFiles, depth)
        return evidenceBundleBuilder.build(radius, sourceResolver)
    }

    fun buildEvidenceBundleForChangeSet(
        changeSet: List<GitChangedFile>,
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        val radius = blastRadiusForChangeSet(changeSet, depth)
        return evidenceBundleBuilder.build(radius, sourceResolver, changeSet)
    }

    fun buildEvidenceBundleForDiff(
        diff: com.charmnight.linkgraph.model.GraphDiff,
        selectedDiffItemIds: List<String> = emptyList(),
        depth: Int = 2,
    ): ReviewEvidenceBundle {
        val selectedEntries = diff.entries.filter { entry -> entry.elementId in selectedDiffItemIds }
            .takeIf(List<*>::isNotEmpty)
            ?: diff.entries
        val changedFiles = GraphDiffChangedFileExtractor.changedFiles(diff.copy(entries = selectedEntries))
        return buildEvidenceBundle(changedFiles, depth)
    }
}

class ReviewEvidenceBundleBuilder {
    fun build(
        radius: BlastRadius,
        sourceResolver: SourceContentResolver? = null,
        gitChangedFiles: List<GitChangedFile> = emptyList(),
    ): ReviewEvidenceBundle {
        val changedEvidence = radius.changedSymbols.map { symbol ->
            mapOf(
                "symbolId" to symbol.symbolId,
                "qualifiedName" to symbol.qualifiedName,
                "filePath" to symbol.filePath,
                "startLine" to symbol.startLine,
                "endLine" to symbol.endLine,
                "changeKind" to symbol.changeKind,
                "baselineOnly" to symbol.baselineOnly,
                "blastRadiusIncomplete" to symbol.blastRadiusIncomplete,
                "unavailableReason" to symbol.unavailableReason,
                "hunkHeader" to symbol.hunk?.header,
                "hunkNewStartLine" to symbol.hunk?.newStartLine,
                "hunkNewLineCount" to symbol.hunk?.newLineCount,
            ) + snippetPayload(
                resolver = sourceResolver,
                filePath = symbol.filePath,
                virtualFileUrl = null,
                startLine = symbol.hunk?.newStartLine ?: symbol.startLine,
                endLine = symbol.hunk?.newStartLine?.let { start ->
                    start + (symbol.hunk.newLineCount ?: 1).coerceAtLeast(1) - 1
                } ?: symbol.endLine,
            )
        }
        val relationEvidence = (radius.spiProviders + radius.reflectionTargets + radius.serviceLoaderLoads + radius.proxyTargets + radius.testRelations)
            .flatMap { relation ->
                relation.samples.map { sample ->
                    mapOf(
                        "relationId" to relation.id,
                        "kind" to relation.kind.name,
                        "confidence" to relation.confidence.name,
                        "filePath" to sample.filePath,
                        "virtualFileUrl" to sample.virtualFileUrl,
                        "startLine" to sample.startLine,
                        "endLine" to sample.endLine,
                        "decompiled" to sample.decompiled,
                        "claim" to sample.claim,
                    ) + snippetPayload(
                        resolver = sourceResolver,
                        filePath = sample.filePath,
                        virtualFileUrl = sample.virtualFileUrl,
                        startLine = sample.startLine,
                        endLine = sample.endLine,
                    )
                }
            }
        val symbolEvidence = (radius.upstream + radius.downstream + radius.relatedTests)
            .take(50)
            .map { symbol ->
                mapOf(
                    "symbolId" to symbol.id,
                    "qualifiedName" to symbol.qualifiedName,
                    "filePath" to symbol.source?.displayPath,
                    "virtualFileUrl" to symbol.source?.virtualFileUrl,
                    "decompiled" to (symbol.source?.decompiled ?: false),
                    "origin" to symbol.origin.name,
                ) + snippetPayload(
                    resolver = sourceResolver,
                    filePath = symbol.source?.displayPath,
                    virtualFileUrl = symbol.source?.virtualFileUrl,
                    startLine = symbol.source?.startLine,
                    endLine = symbol.source?.endLine,
                )
            }
        return ReviewEvidenceBundle(
            changedSymbols = radius.changedSymbols,
            blastRadius = radius,
            evidenceRefs = changedEvidence + relationEvidence + symbolEvidence,
            gitChangedFiles = gitChangedFiles,
        )
    }

    private fun snippetPayload(
        resolver: SourceContentResolver?,
        filePath: String?,
        virtualFileUrl: String?,
        startLine: Int?,
        endLine: Int?,
    ): Map<String, Any?> {
        resolver ?: return mapOf("snippetUnavailableReason" to "SOURCE_RESOLVER_UNAVAILABLE")
        val path = virtualFileUrl ?: filePath ?: return mapOf("snippetUnavailableReason" to "SOURCE_PATH_UNAVAILABLE")
        val content = resolver.readSnippetByPath(path, startLine, endLine)
            ?: resolver.readByPath(path)
            ?: return mapOf("snippetUnavailableReason" to "SOURCE_SNIPPET_UNAVAILABLE")
        return mapOf(
            "snippet" to content.text.take(2_000),
            "snippetOrigin" to content.origin.name,
            "snippetVirtualFileUrl" to content.virtualFileUrl,
            "snippetStartLine" to content.startLine,
            "snippetEndLine" to content.endLine,
            "snippetDecompiled" to content.decompiled,
        )
    }
}
