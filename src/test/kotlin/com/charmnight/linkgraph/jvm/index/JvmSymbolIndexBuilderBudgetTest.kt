package com.charmnight.linkgraph.jvm.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmSymbolIndexBuilderBudgetTest {
    @Test
    fun projectScopeClassScansAreSkippedWhenProjectClassBudgetIsExhausted() {
        val source = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/jvm/index/JvmSymbolIndexBuilder.kt"))
        val buildBeforeFallback = Regex(
            """traceStage\("jvmSymbolIndex\.contentRoots"\)[\s\S]*?if \(classes\.isEmpty\(\) && budget\.maxProjectClasses > 0\)""",
        ).find(source)?.value ?: error("build section around project-scope scan not found")
        val projectScopeMethod = Regex(
            """private fun indexProjectScopeClasses[\s\S]*?\n    private fun indexResource""",
        ).find(source)?.value ?: error("indexProjectScopeClasses method not found")

        assertTrue(
            buildBeforeFallback.contains("if (classes.size < budget.maxProjectClasses)") &&
                buildBeforeFallback.contains("indexProjectScopeClasses("),
            "build() must not call project-scope PSI scans after content-root indexing already exhausted maxProjectClasses.",
        )
        assertTrue(
            Regex("""if \(classes\.size >= budget\.maxProjectClasses\)\s*\{\s*return\s*\}""").containsMatchIn(projectScopeMethod),
            "indexProjectScopeClasses must return before AllClassesSearch when class budget is exhausted.",
        )
        assertTrue(
            Regex("""if \(classes\.size < budget\.maxProjectClasses\)[\s\S]*?AllClassesSearch\.search""")
                .containsMatchIn(projectScopeMethod),
            "AllClassesSearch must be guarded by remaining project-class budget.",
        )
        assertTrue(
            Regex("""if \(classes\.size < budget\.maxProjectClasses\)[\s\S]*?PsiShortNamesCache\.getInstance""")
                .containsMatchIn(projectScopeMethod),
            "PsiShortNamesCache must be guarded by remaining project-class budget.",
        )
    }
}
