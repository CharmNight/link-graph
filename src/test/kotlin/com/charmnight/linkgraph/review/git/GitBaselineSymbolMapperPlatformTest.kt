package com.charmnight.linkgraph.review.git

import com.charmnight.linkgraph.jvm.index.PsiJvmSourceTextSymbolExtractor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class GitBaselineSymbolMapperPlatformTest : BasePlatformTestCase() {
    fun testBaselineMappingUsesPsiExtractorWhenProjectIsAvailable() {
        val changeSet = listOf(
            GitChangedFile(
                oldPath = "src/main/java/com/example/LegacyService.java",
                newPath = "src/main/java/com/example/LegacyService.java",
                changeKind = GitChangeKind.MODIFIED,
                hunks = listOf(
                    GitHunk(
                        oldStart = 3,
                        oldLineCount = 1,
                        newStart = 3,
                        newLineCount = 0,
                        header = "@@ -3,1 +3,0 @@",
                        lines = listOf("-    void removed() {}"),
                    ),
                ),
            ),
        )
        val mapper = GitBaselineSymbolMapper(
            object : GitChangeSetProvider(null) {
                override fun readHeadFile(path: String): String? =
                    """
                    package com.example;
                    class LegacyService {
                        void removed() {}
                        void kept() {}
                    }
                    """.trimIndent()
            },
            PsiJvmSourceTextSymbolExtractor(project),
        )

        val symbols = mapper.mapBaselineOnlySymbols(changeSet)

        assertTrue(symbols.any { symbol ->
            symbol.baselineOnly &&
                symbol.qualifiedName == "com.example.LegacyService.removed():void" &&
                symbol.hunk?.oldStartLine == 3 &&
                symbol.blastRadiusIncomplete &&
                symbol.unavailableReason == null
        }, symbols.joinToString("\n"))
        assertTrue(symbols.none { symbol -> symbol.qualifiedName == "com.example.LegacyService.kept():void" })
        assertTrue(symbols.none { symbol -> symbol.unavailableReason == "BASELINE_REGEX_SYMBOL_MAPPING" })
    }
}
