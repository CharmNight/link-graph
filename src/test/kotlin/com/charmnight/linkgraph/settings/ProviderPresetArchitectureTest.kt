package com.charmnight.linkgraph.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderPresetArchitectureTest {
    @Test
    fun codebaseDoesNotReferenceLegacyProviderTypeShell() {
        val projectRoot = Path.of("").toAbsolutePath()
        val roots = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/test/kotlin"),
            projectRoot.resolve("src/integrationTest/kotlin"),
        )
        val offendingFiles = roots
            .filter(Files::exists)
            .flatMap(::kotlinFilesUnder)
            .filter { file ->
                val text = Files.readString(file)
                legacyProviderTypeToken() in text || legacyProviderTypeFunctionToken() in text
            }
            .map { projectRoot.relativize(it).toString() }
            .sorted()

        assertTrue(
            offendingFiles.isEmpty(),
            "legacy provider compatibility shell must be removed, found references in: $offendingFiles",
        )
    }

    private fun legacyProviderTypeToken(): String = listOf("Llm", "Provider", "Type").joinToString("")

    private fun legacyProviderTypeFunctionToken(): String = listOf("provider", "Type(").joinToString("")

    private fun kotlinFilesUnder(root: Path): List<Path> {
        Files.walk(root).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .toList()
        }
    }
}
