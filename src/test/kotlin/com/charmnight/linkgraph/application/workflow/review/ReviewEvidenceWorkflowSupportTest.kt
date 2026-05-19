package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.charmnight.linkgraph.review.git.GitChangedFile
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewEvidenceWorkflowSupportTest : BasePlatformTestCase() {
    fun testSelectedDiffWithoutExtractablePathDoesNotFallbackToWholeWorkingTree() {
        val calls = mutableListOf<List<String>>()
        val support = ReviewEvidenceWorkflowSupport(project) {
            object : GitChangeSetProvider(null) {
                override fun workingTreeChangeSet(selectedPaths: List<String>): List<GitChangedFile> {
                    calls += selectedPaths
                    error("selected diff without paths must not query working tree")
                }
            }
        }
        val service = ReviewGraphQueryService(
            ArchitectureGraphIndex.from(
                symbolIndex = JvmSymbolIndex(),
                relationIndex = JvmRelationIndex(),
            ),
        )
        val diff = GraphDiff(
            entries = listOf(
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "node-without-path",
                    status = DiffStatus.MODIFIED,
                    message = "business node changed",
                ),
            ),
        )

        val result = support.buildEvidence(diff, listOf("node-without-path"), service)

        assertEquals(emptyList<List<String>>(), calls)
        assertTrue(result.bundle.gitChangedFiles.isEmpty())
        assertTrue(result.warnings.any { warning -> warning.contains("不再回退到全量工作区") })
    }

    fun testUnselectedDiffStillUsesWholeWorkingTreeFallback() {
        val calls = mutableListOf<List<String>>()
        val support = ReviewEvidenceWorkflowSupport(project) {
            object : GitChangeSetProvider(null) {
                override fun workingTreeChangeSet(selectedPaths: List<String>): List<GitChangedFile> {
                    calls += selectedPaths
                    return emptyList()
                }
            }
        }
        val changed = classSymbol("com.example.OrderService", "src/main/java/com/example/OrderService.java")
        val service = ReviewGraphQueryService(
            ArchitectureGraphIndex.from(
                symbolIndex = JvmSymbolIndex(
                    classesByQualifiedName = mapOf(changed.qualifiedName to changed),
                ),
                relationIndex = JvmRelationIndex(),
            ),
        )
        val diff = GraphDiff(
            entries = listOf(
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = changed.id,
                    status = DiffStatus.MODIFIED,
                    message = changed.source!!.displayPath,
                ),
            ),
        )

        val result = support.buildEvidence(diff, emptyList(), service)

        assertEquals(listOf(listOf("src/main/java/com/example/OrderService.java")), calls)
        assertTrue(result.bundle.changedSymbols.any { symbol -> symbol.symbolId == changed.id })
        assertEquals(emptyList<String>(), result.warnings)
    }

    private fun classSymbol(qualifiedName: String, path: String): JvmClassSymbol =
        JvmClassSymbol(
            id = stableJvmId("class", qualifiedName),
            qualifiedName = qualifiedName,
            simpleName = qualifiedName.substringAfterLast('.'),
            packageName = qualifiedName.substringBeforeLast('.'),
            moduleName = null,
            kind = JvmClassKind.CLASS,
            source = JvmSourceRef(
                displayPath = path,
                virtualFileUrl = null,
                startLine = 1,
                endLine = 10,
                decompiled = false,
            ),
            origin = SourceOrigin.PROJECT_SOURCE,
        )
}
