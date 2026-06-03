package com.charmnight.linkgraph.investigation.resolving.java

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaPsiEvidenceSupportTest {
    @Test
    fun investigationSymbolSupportUsesJvmIndexInsteadOfPsiFallbackScan() {
        val root = Path.of("").toAbsolutePath()
        assertFalse(
            Files.exists(root.resolve("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JavaPsiEvidenceSupport.kt")),
            "Investigation should not keep the old PSI evidence support path.",
        )
        val source = Files.readString(
            root.resolve("src/main/kotlin/com/charmnight/linkgraph/investigation/resolving/java/JvmInvestigationEvidenceSupport.kt"),
        )

        assertTrue(source.contains("ArchitectureGraphIndex"))
        listOf(
            "JavaPsiFacade",
            "PsiShortNamesCache",
            "FilenameIndex",
            "PsiClass",
            "PsiMethod",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "Investigation symbol support must not keep PSI fallback: $forbidden")
        }
    }
}
