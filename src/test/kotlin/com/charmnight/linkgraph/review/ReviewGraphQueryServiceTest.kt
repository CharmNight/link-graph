package com.charmnight.linkgraph.review

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbol
import com.charmnight.linkgraph.jvm.index.JvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.jvm.index.UnavailableJvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.jvm.relation.JvmEvidenceRef
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationSource
import com.charmnight.linkgraph.jvm.index.stableJvmId
import com.charmnight.linkgraph.review.git.GitBaselineSymbolMapper
import com.charmnight.linkgraph.review.git.GitChangeKind
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.charmnight.linkgraph.review.git.GitChangedFile
import com.charmnight.linkgraph.review.git.GitHunk
import com.charmnight.linkgraph.source.SourceContent
import com.charmnight.linkgraph.source.SourceContentResolver
import com.charmnight.linkgraph.source.SourceOrigin
import com.charmnight.linkgraph.testing.assertReviewGraphViewDataContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewGraphQueryServiceTest {
    @Test
    fun mapsChangedFilesAndBuildsBlastRadius() {
        val changed = classSymbol("com.example.OrderService", "src/main/java/com/example/OrderService.java")
        val caller = classSymbol("com.example.OrderController", "src/main/java/com/example/OrderController.java")
        val test = classSymbol("com.example.OrderServiceTest", "src/test/java/com/example/OrderServiceTest.java", testSource = true)
        val changedMethod = methodSymbol(changed, "load", 4, 6)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(changed, caller, test).associateBy(JvmClassSymbol::qualifiedName),
                methodsBySignature = listOf(changedMethod).associateBy(JvmMethodSymbol::signature),
            ),
            relationIndex = JvmRelationIndex(
                relations = listOf(
                    relation(JvmRelationKind.CALLS, caller, changed),
                    relation(JvmRelationKind.CALLS, test, changed, metadata = mapOf("test.framework" to "true")),
                    relation(JvmRelationKind.REFLECTS_TO, changed, caller),
                    methodRelation(JvmRelationKind.SERVICE_LOADER_LOADS, changedMethod, caller),
                ),
            ),
        )
        val service = ReviewGraphQueryService(index)

        val radius = service.blastRadius(listOf("src/main/java/com/example/OrderService.java"))

        assertTrue(radius.changedSymbols.any { symbol -> symbol.symbolId == changed.id })
        assertTrue(radius.upstream.any { symbol -> symbol.id == caller.id })
        assertTrue(radius.relatedTests.any { symbol -> symbol.id == test.id })
        assertEquals(1, radius.reflectionTargets.size)
        assertEquals(1, radius.serviceLoaderLoads.size)
        val bundle = service.buildEvidenceBundle(listOf("src/main/java/com/example/OrderService.java"))
        assertTrue(bundle.evidenceRefs.any { ref ->
            ref["kind"] == JvmRelationKind.SERVICE_LOADER_LOADS.name
        })
        assertTrue(bundle.evidenceRefs.isNotEmpty())
        assertEquals(listOf("com.example"), radius.affectedPackages)
    }

    @Test
    fun mapsUnifiedDiffHunksToMethodSymbols() {
        val changed = classSymbol("com.example.OrderService", "src/main/java/com/example/OrderService.java")
        val changedMethod = methodSymbol(changed, "apply", 20, 30)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = mapOf(changed.qualifiedName to changed),
                methodsBySignature = mapOf(changedMethod.signature to changedMethod),
            ),
            relationIndex = JvmRelationIndex(),
        )
        val diff = """
            diff --git a/src/main/java/com/example/OrderService.java b/src/main/java/com/example/OrderService.java
            --- a/src/main/java/com/example/OrderService.java
            +++ b/src/main/java/com/example/OrderService.java
            @@ -18,3 +22,4 @@
            - old
            + new
        """.trimIndent()

        val changedSymbols = ReviewGraphQueryService(index).changedSymbols(listOf(diff))

        assertTrue(changedSymbols.any { symbol ->
            symbol.symbolId == changedMethod.id &&
                symbol.changeKind == "HUNK_MODIFIED" &&
                symbol.hunk?.newStartLine == 22
        })
        assertTrue(changedSymbols.none { symbol -> symbol.symbolId == changed.id && symbol.changeKind == "HUNK_MODIFIED" })
    }

    @Test
    fun mapsDeletedUnifiedDiffHunksByOldPathAndOldLineRange() {
        val deleted = classSymbol("com.example.legacy.LegacyService", "src/main/java/com/example/legacy/LegacyService.java")
        val deletedMethod = methodSymbol(deleted, "remove", 4, 8)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = mapOf(deleted.qualifiedName to deleted),
                methodsBySignature = mapOf(deletedMethod.signature to deletedMethod),
            ),
            relationIndex = JvmRelationIndex(),
        )
        val diff = """
            diff --git a/src/main/java/com/example/legacy/LegacyService.java b/src/main/java/com/example/legacy/LegacyService.java
            deleted file mode 100644
            --- a/src/main/java/com/example/legacy/LegacyService.java
            +++ /dev/null
            @@ -4,5 +0,0 @@
            - void remove() {}
        """.trimIndent()

        val changedSymbols = ReviewGraphQueryService(index).changedSymbols(listOf(diff))

        assertTrue(changedSymbols.any { symbol ->
            symbol.symbolId == deletedMethod.id &&
                symbol.changeKind == "HUNK_DELETED" &&
                symbol.hunk?.oldFilePath == "src/main/java/com/example/legacy/LegacyService.java"
        })
    }

    @Test
    fun mapsRenamedUnifiedDiffHunksByOldAndNewPaths() {
        val oldClass = classSymbol("com.example.OldBillingService", "src/main/java/com/example/OldBillingService.java")
        val newClass = classSymbol("com.example.NewBillingService", "src/main/java/com/example/NewBillingService.java")
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(oldClass, newClass).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(),
        )
        val diff = """
            diff --git a/src/main/java/com/example/OldBillingService.java b/src/main/java/com/example/NewBillingService.java
            similarity index 89%
            rename from src/main/java/com/example/OldBillingService.java
            rename to src/main/java/com/example/NewBillingService.java
            --- a/src/main/java/com/example/OldBillingService.java
            +++ b/src/main/java/com/example/NewBillingService.java
            @@ -1,6 +1,7 @@
            + class NewBillingService {}
        """.trimIndent()

        val changedSymbols = ReviewGraphQueryService(index).changedSymbols(listOf(diff))

        assertTrue(changedSymbols.any { symbol -> symbol.symbolId == oldClass.id && symbol.changeKind == "HUNK_RENAMED" })
        assertTrue(changedSymbols.any { symbol -> symbol.symbolId == newClass.id && symbol.changeKind == "HUNK_RENAMED" })
    }

    @Test
    fun evidenceBundleIncludesSourceSnippetsButDoesNotGuessPackageTestCandidates() {
        val changed = classSymbol("com.example.billing.InvoiceService", "src/main/java/com/example/billing/InvoiceService.java")
        val test = classSymbol("com.example.billing.InvoiceServiceTest", "src/test/java/com/example/billing/InvoiceServiceTest.java", testSource = true)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(changed, test).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(),
        )
        val resolver = mapResolver(
            mapOf(
                "src/main/java/com/example/billing/InvoiceService.java" to "package com.example.billing;\nclass InvoiceService { void bill() {} }\n",
                "src/test/java/com/example/billing/InvoiceServiceTest.java" to "package com.example.billing;\nclass InvoiceServiceTest {}\n",
            ),
        )

        val bundle = ReviewGraphQueryService(index, sourceResolver = resolver)
            .buildEvidenceBundle(listOf("src/main/java/com/example/billing/InvoiceService.java"))

        assertTrue(bundle.blastRadius.relatedTests.none { symbol -> symbol.id == test.id })
        assertEquals(listOf("com.example.billing"), bundle.blastRadius.affectedPackages)
        assertTrue(bundle.evidenceRefs.any { ref ->
            ref["symbolId"] == changed.id && ref["snippet"].toString().contains("InvoiceService")
        })
    }

    @Test
    fun relatedTestsComeFromIndexedRelations() {
        val changed = classSymbol("com.example.billing.InvoiceService", "src/main/java/com/example/billing/InvoiceService.java")
        val test = classSymbol("com.example.billing.InvoiceServiceTest", "src/test/java/com/example/billing/InvoiceServiceTest.java", testSource = true)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(changed, test).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                relations = listOf(relation(JvmRelationKind.CALLS, test, changed, metadata = mapOf("test.framework" to "true"))),
            ),
        )

        val radius = ReviewGraphQueryService(index)
            .blastRadius(listOf("src/main/java/com/example/billing/InvoiceService.java"))

        assertEquals(listOf(test.id), radius.relatedTests.map(JvmSymbol::id))
    }

    @Test
    fun relatedTestsDoNotUseNameOrPathHeuristics() {
        val changed = classSymbol("com.example.billing.InvoiceService", "src/main/java/com/example/billing/InvoiceService.java")
        val misleadingProductionClass = classSymbol(
            "com.example.billing.InvoiceServiceTest",
            "src/main/java/com/example/billing/InvoiceServiceTest.java",
        )
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(changed, misleadingProductionClass).associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                relations = listOf(relation(JvmRelationKind.CALLS, misleadingProductionClass, changed)),
            ),
        )

        val radius = ReviewGraphQueryService(index)
            .blastRadius(listOf("src/main/java/com/example/billing/InvoiceService.java"))

        assertEquals(emptyList(), radius.relatedTests.map(JvmSymbol::id))
    }

    @Test
    fun relatedTestsPreferTestMethodSymbolsFromTestsRelations() {
        val changed = classSymbol("com.example.billing.InvoiceService", "src/main/java/com/example/billing/InvoiceService.java")
        val test = classSymbol("com.example.billing.InvoiceContractSpec", "src/test/java/com/example/billing/InvoiceContractSpec.java", testSource = true)
        val changedMethod = methodSymbol(changed, "bill", 3, 5)
        val testMethod = methodSymbol(test, "coversInvoices", 3, 5)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(changed, test).associateBy(JvmClassSymbol::qualifiedName),
                methodsBySignature = listOf(changedMethod, testMethod).associateBy(JvmMethodSymbol::signature),
            ),
            relationIndex = JvmRelationIndex(
                relations = listOf(
                    methodToMethodRelation(
                        JvmRelationKind.TESTS,
                        from = testMethod,
                        to = changedMethod,
                        metadata = mapOf("test.reason" to "CALL_PATH"),
                    ),
                ),
            ),
        )

        val radius = ReviewGraphQueryService(index)
            .blastRadius(listOf("src/main/java/com/example/billing/InvoiceService.java"))

        assertEquals(listOf(testMethod.id), radius.relatedTests.map(JvmSymbol::id))
        assertEquals("CALL_PATH", radius.relatedTestReasons[testMethod.id])
    }

    @Test
    fun projectorKeepsBlastRadiusEdgesTiedToTheirChangedSymbol() {
        val firstChanged = classSymbol("com.example.FirstService", "src/main/java/com/example/FirstService.java")
        val secondChanged = classSymbol("com.example.SecondService", "src/main/java/com/example/SecondService.java")
        val firstCaller = classSymbol("com.example.FirstController", "src/main/java/com/example/FirstController.java")
        val secondCaller = classSymbol("com.example.SecondController", "src/main/java/com/example/SecondController.java")
        val firstTest = classSymbol("com.example.FirstServiceTest", "src/test/java/com/example/FirstServiceTest.java", testSource = true)
        val secondTest = classSymbol("com.example.SecondServiceTest", "src/test/java/com/example/SecondServiceTest.java", testSource = true)
        val index = ArchitectureGraphIndex.from(
            symbolIndex = JvmSymbolIndex(
                classesByQualifiedName = listOf(firstChanged, secondChanged, firstCaller, secondCaller, firstTest, secondTest)
                    .associateBy(JvmClassSymbol::qualifiedName),
            ),
            relationIndex = JvmRelationIndex(
                relations = listOf(
                    relation(JvmRelationKind.CALLS, firstCaller, firstChanged),
                    relation(JvmRelationKind.CALLS, secondCaller, secondChanged),
                    relation(JvmRelationKind.CALLS, firstTest, firstChanged, metadata = mapOf("test.framework" to "true")),
                    relation(JvmRelationKind.CALLS, secondTest, secondChanged, metadata = mapOf("test.framework" to "true")),
                ),
            ),
        )

        val view = ReviewGraphProjector().project(
            ReviewGraphQueryService(index).buildEvidenceBundle(
                listOf(
                    "src/main/java/com/example/FirstService.java",
                    "src/main/java/com/example/SecondService.java",
                ),
            ),
        )
        val edgeIds = view.visibleGraph.edges.map { edge -> edge.id }.toSet()

        assertTrue("first caller must remain tied to first changed symbol") {
            "review:upstream:${firstCaller.id}->${firstChanged.id}" in edgeIds
        }
        assertTrue("second caller must remain tied to second changed symbol") {
            "review:upstream:${secondCaller.id}->${secondChanged.id}" in edgeIds
        }
        assertTrue("first test must remain tied to first changed symbol") {
            "review:test:${firstChanged.id}->${firstTest.id}" in edgeIds
        }
        assertTrue("second test must remain tied to second changed symbol") {
            "review:test:${secondChanged.id}->${secondTest.id}" in edgeIds
        }
        assertTrue("first caller must not be projected as an upstream of second changed symbol") {
            "review:upstream:${firstCaller.id}->${secondChanged.id}" !in edgeIds
        }
        assertTrue("second caller must not be projected as an upstream of first changed symbol") {
            "review:upstream:${secondCaller.id}->${firstChanged.id}" !in edgeIds
        }
        assertTrue("first test must not be projected as related to second changed symbol") {
            "review:test:${secondChanged.id}->${firstTest.id}" !in edgeIds
        }
        assertTrue("second test must not be projected as related to first changed symbol") {
            "review:test:${firstChanged.id}->${secondTest.id}" !in edgeIds
        }
    }

    @Test
    fun deletedFilesCanProduceBaselineOnlyEvidence() {
        val changeSet = listOf(
            GitChangedFile(
                oldPath = "src/main/java/com/example/LegacyService.java",
                newPath = null,
                changeKind = GitChangeKind.DELETED,
                hunks = listOf(GitHunk(oldStart = 1, oldLineCount = 5, newStart = null, newLineCount = 0, header = "@@ -1,5 +0,0 @@")),
            ),
        )
        val baselineMapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? =
                    "package com.example;\nclass LegacyService { void oldPath() {} }\n"
            },
            fixedBaselineSymbols(
                JvmSourceTextSymbol(
                    kind = "class",
                    qualifiedName = "com.example.LegacyService",
                    startLine = 1,
                    endLine = 1,
                ),
            ),
        )
        val service = ReviewGraphQueryService(
            index = ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex()),
            baselineSymbolMapper = baselineMapper,
        )

        val bundle = service.buildEvidenceBundleForChangeSet(changeSet)

        assertTrue(bundle.changedSymbols.any { symbol ->
            symbol.qualifiedName == "com.example.LegacyService" &&
                symbol.baselineOnly &&
                symbol.blastRadiusIncomplete &&
                symbol.changeKind == GitChangeKind.DELETED.name &&
                symbol.unavailableReason == null
            })
    }

    @Test
    fun baselineOnlyEvidenceDoesNotRegexGuessWhenPsiSymbolMappingIsUnavailable() {
        val changeSet = listOf(
            GitChangedFile(
                oldPath = "src/main/java/com/example/LegacyService.java",
                newPath = null,
                changeKind = GitChangeKind.DELETED,
                hunks = listOf(GitHunk(oldStart = 1, oldLineCount = 5, newStart = null, newLineCount = 0, header = "@@ -1,5 +0,0 @@")),
            ),
        )
        val baselineMapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? =
                    "package com.example;\nclass LegacyService { void oldPath() {} }\n"
            },
            UnavailableJvmSourceTextSymbolExtractor,
        )
        val service = ReviewGraphQueryService(
            index = ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex()),
            baselineSymbolMapper = baselineMapper,
        )

        val bundle = service.buildEvidenceBundleForChangeSet(changeSet)

        assertEquals(listOf("BASELINE_SYMBOL_UNAVAILABLE"), bundle.changedSymbols.map(ChangedSymbol::unavailableReason))
        assertTrue(bundle.changedSymbols.none { symbol -> symbol.qualifiedName == "com.example.LegacyService" })
        assertTrue(bundle.changedSymbols.none { symbol -> symbol.unavailableReason == "BASELINE_REGEX_SYMBOL_MAPPING" })
    }

    @Test
    fun nonJvmFilesDoNotProduceBaselineOnlyJvmEvidence() {
        val changeSet = listOf(
            GitChangedFile(
                oldPath = "docs/architecture.md",
                newPath = "docs/architecture.md",
                changeKind = GitChangeKind.MODIFIED,
                hunks = listOf(
                    GitHunk(
                        oldStart = 1,
                        oldLineCount = 3,
                        newStart = 1,
                        newLineCount = 2,
                        header = "@@ -1,3 +1,2 @@",
                        lines = listOf("- old docs line", "+ new docs line"),
                    ),
                ),
            ),
        )
        val baselineMapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? = "# Architecture\n"
            },
            fixedBaselineSymbols(
                JvmSourceTextSymbol(
                    kind = "class",
                    qualifiedName = "docs.architecture",
                    startLine = 1,
                    endLine = 1,
                ),
            ),
        )
        val service = ReviewGraphQueryService(
            index = ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex()),
            baselineSymbolMapper = baselineMapper,
        )

        val bundle = service.buildEvidenceBundleForChangeSet(changeSet)

        assertEquals(emptyList(), bundle.changedSymbols)
        assertEquals(changeSet, bundle.gitChangedFiles)
    }

    @Test
    fun projectorExposesStructuredDiffAndEvidenceDetails() {
        val changeSet = listOf(
            GitChangedFile(
                oldPath = null,
                newPath = "src/main/java/com/example/NewService.java",
                changeKind = GitChangeKind.ADDED,
                hunks = listOf(GitHunk(oldStart = null, oldLineCount = 0, newStart = 1, newLineCount = 4, header = "@@ -0,0 +1,4 @@")),
            ),
            GitChangedFile(
                oldPath = "src/main/java/com/example/LegacyService.java",
                newPath = null,
                changeKind = GitChangeKind.DELETED,
                hunks = listOf(GitHunk(oldStart = 1, oldLineCount = 5, newStart = null, newLineCount = 0, header = "@@ -1,5 +0,0 @@")),
            ),
        )
        val baselineMapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? =
                    "package com.example;\nclass LegacyService { void oldPath() {} }\n"
            },
            fixedBaselineSymbols(
                JvmSourceTextSymbol(
                    kind = "class",
                    qualifiedName = "com.example.LegacyService",
                    startLine = 1,
                    endLine = 1,
                ),
            ),
        )
        val service = ReviewGraphQueryService(
            index = ArchitectureGraphIndex.from(JvmSymbolIndex(), JvmRelationIndex()),
            baselineSymbolMapper = baselineMapper,
        )

        val view = ReviewGraphProjector().project(service.buildEvidenceBundleForChangeSet(changeSet))

        assertReviewGraphViewDataContract(view, "review.projector.structuredDiff")
        assertEquals(2, view.changedFiles.size)
        assertTrue(view.changedHunks.any { hunk -> hunk.changeKind == "HUNK_ADDED" })
        assertTrue(view.unmatchedHunks.any { hunk -> hunk.newFilePath == "src/main/java/com/example/NewService.java" })
        assertTrue(view.baselineOnlySymbols.any { symbol -> symbol.qualifiedName == "com.example.LegacyService" })
        assertTrue(view.evidenceSnippets.any { evidence -> evidence.unavailableReason == "SOURCE_RESOLVER_UNAVAILABLE" })
    }

    @Test
    fun projectorKeepsLargeReviewGraphBoundedButCountsFullGraph() {
        val changedSymbols = (1..260).map { index ->
            ChangedSymbol(
                symbolId = "symbol:$index",
                qualifiedName = "com.example.Service$index",
                filePath = "src/main/java/com/example/Service$index.java",
                startLine = 1,
                endLine = 5,
            )
        }
        val bundle = ReviewEvidenceBundle(
            changedSymbols = changedSymbols,
            blastRadius = BlastRadius(
                changedSymbols = changedSymbols,
                upstream = emptyList(),
                downstream = emptyList(),
                spiProviders = emptyList(),
                reflectionTargets = emptyList(),
                serviceLoaderLoads = emptyList(),
                proxyTargets = emptyList(),
                relatedTests = emptyList(),
            ),
            evidenceRefs = emptyList(),
        )

        val view = ReviewGraphProjector().project(bundle)

        assertReviewGraphViewDataContract(view, "review.projector.large")
        assertEquals(260, view.fullGraph.nodes.size)
        assertEquals(240, view.visibleGraph.nodes.size)
        assertTrue(view.summary.truncated)
        assertEquals(20, view.summary.hiddenNodeCount)
        assertEquals(260, view.summary.changedSymbolCount)
        assertEquals(view.visibleGraph.nodes.size, view.projectionIndex.nodeMappings.size)
    }

    @Test
    fun methodMoveKeepsBaselineOnlyOldMethodEvidence() {
        val newOwner = classSymbol("com.example.NewService", "src/main/java/com/example/NewService.java")
        val movedMethod = methodSymbol(newOwner, "moved", 3, 3)
        val changeSet = listOf(
            GitChangedFile(
                oldPath = "src/main/java/com/example/OldService.java",
                newPath = "src/main/java/com/example/OldService.java",
                changeKind = GitChangeKind.MODIFIED,
                hunks = listOf(
                    GitHunk(
                        oldStart = 3,
                        oldLineCount = 1,
                        newStart = 3,
                        newLineCount = 0,
                        header = "@@ -3,1 +3,0 @@",
                        lines = listOf("-    void moved() {}"),
                    ),
                ),
            ),
            GitChangedFile(
                oldPath = "src/main/java/com/example/NewService.java",
                newPath = "src/main/java/com/example/NewService.java",
                changeKind = GitChangeKind.MODIFIED,
                hunks = listOf(
                    GitHunk(
                        oldStart = 2,
                        oldLineCount = 0,
                        newStart = 3,
                        newLineCount = 1,
                        header = "@@ -2,0 +3,1 @@",
                        lines = listOf("+    void moved() {}"),
                    ),
                ),
            ),
        )
        val baselineMapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? =
                    when (path) {
                        "src/main/java/com/example/OldService.java" ->
                            "package com.example;\nclass OldService {\n    void moved() {}\n    void kept() {}\n}\n"
                        else -> null
                    }
            },
            fixedBaselineSymbols(
                JvmSourceTextSymbol(
                    kind = "method",
                    qualifiedName = "com.example.OldService.moved():void",
                    startLine = 3,
                    endLine = 3,
                ),
                JvmSourceTextSymbol(
                    kind = "method",
                    qualifiedName = "com.example.OldService.kept():void",
                    startLine = 4,
                    endLine = 4,
                ),
            ),
        )
        val service = ReviewGraphQueryService(
            index = ArchitectureGraphIndex.from(
                symbolIndex = JvmSymbolIndex(
                    classesByQualifiedName = mapOf(newOwner.qualifiedName to newOwner),
                    methodsBySignature = mapOf(movedMethod.signature to movedMethod),
                ),
                relationIndex = JvmRelationIndex(),
            ),
            baselineSymbolMapper = baselineMapper,
        )

        val bundle = service.buildEvidenceBundleForChangeSet(changeSet)

        assertTrue(bundle.changedSymbols.any { symbol -> symbol.symbolId == movedMethod.id })
        assertTrue(bundle.changedSymbols.any { symbol ->
                symbol.baselineOnly &&
                symbol.qualifiedName == "com.example.OldService.moved():void" &&
                symbol.hunk?.oldStartLine == 3 &&
                symbol.unavailableReason == null
        })
        assertTrue(bundle.changedSymbols.none { symbol -> symbol.qualifiedName == "com.example.OldService.kept():void" })
    }

    private fun fixedBaselineSymbols(vararg symbols: JvmSourceTextSymbol): JvmSourceTextSymbolExtractor =
        JvmSourceTextSymbolExtractor { _, _ -> symbols.toList() }

    private fun classSymbol(qualifiedName: String, path: String, testSource: Boolean = false): JvmClassSymbol =
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
            testSource = testSource,
        )

    private fun relation(
        kind: JvmRelationKind,
        from: JvmClassSymbol,
        to: JvmClassSymbol,
        metadata: Map<String, String> = emptyMap(),
    ): JvmRelation =
        JvmRelation(
            id = "$kind:${from.id}->${to.id}",
            kind = kind,
            fromSymbolId = from.id,
            toSymbolId = to.id,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            samples = listOf(
                JvmEvidenceRef(
                    filePath = from.source?.displayPath,
                    virtualFileUrl = null,
                    startLine = 1,
                    endLine = 2,
                    claim = "$kind ${to.qualifiedName}",
                ),
            ),
            metadata = metadata,
        )

    private fun methodRelation(
        kind: JvmRelationKind,
        from: JvmMethodSymbol,
        to: JvmClassSymbol,
        metadata: Map<String, String> = emptyMap(),
    ): JvmRelation =
        JvmRelation(
            id = "$kind:${from.id}->${to.id}",
            kind = kind,
            fromSymbolId = from.id,
            toSymbolId = to.id,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            samples = listOf(
                JvmEvidenceRef(
                    filePath = from.source?.displayPath,
                    virtualFileUrl = null,
                    startLine = from.source?.startLine,
                    endLine = from.source?.endLine,
                    claim = "$kind ${to.qualifiedName}",
                ),
            ),
            metadata = metadata,
        )

    private fun methodToMethodRelation(
        kind: JvmRelationKind,
        from: JvmMethodSymbol,
        to: JvmMethodSymbol,
        metadata: Map<String, String> = emptyMap(),
    ): JvmRelation =
        JvmRelation(
            id = "$kind:${from.id}->${to.id}",
            kind = kind,
            fromSymbolId = from.id,
            toSymbolId = to.id,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            samples = listOf(
                JvmEvidenceRef(
                    filePath = from.source?.displayPath,
                    virtualFileUrl = null,
                    startLine = from.source?.startLine,
                    endLine = from.source?.endLine,
                    claim = "$kind ${to.qualifiedName}",
                ),
            ),
            metadata = metadata,
        )

    private fun methodSymbol(
        owner: JvmClassSymbol,
        name: String,
        startLine: Int,
        endLine: Int,
    ): JvmMethodSymbol {
        val signature = "${owner.qualifiedName}.$name():void"
        return JvmMethodSymbol(
            id = stableJvmId("method", signature),
            qualifiedName = signature,
            simpleName = name,
            ownerClassName = owner.qualifiedName,
            signature = signature,
            parameterTypes = emptyList(),
            returnType = "void",
            source = JvmSourceRef(
                displayPath = owner.source?.displayPath.orEmpty(),
                virtualFileUrl = null,
                startLine = startLine,
                endLine = endLine,
                decompiled = false,
            ),
            origin = SourceOrigin.PROJECT_SOURCE,
        )
    }

    private fun mapResolver(contentByPath: Map<String, String>): SourceContentResolver =
        object : SourceContentResolver {
            override fun readByVirtualFileUrl(url: String): SourceContent? = readByPath(url)

            override fun readByPath(path: String): SourceContent? {
                val text = contentByPath[path] ?: return null
                return SourceContent(
                    text = text,
                    displayPath = path,
                    virtualFileUrl = null,
                    origin = SourceOrigin.PROJECT_SOURCE,
                    language = "JAVA",
                    startLine = 1,
                    endLine = text.lineSequence().count().coerceAtLeast(1),
                )
            }

            override fun readSnippetByPath(path: String, startLine: Int?, endLine: Int?): SourceContent? =
                readByPath(path)?.let { content ->
                    if (startLine == null || endLine == null) {
                        content
                    } else {
                        val lines = content.text.lines()
                        content.copy(
                            text = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size))
                                .joinToString("\n"),
                            startLine = startLine,
                            endLine = endLine,
                        )
                    }
                }

            override fun readClassByQualifiedName(qualifiedName: String): SourceContent? = null

            override fun readResourceByPath(resourcePath: String): SourceContent? = readByPath(resourcePath)
        }
}
