package com.charmnight.linkgraph.semantic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticArchitectureBoundaryTest {
    private val projectRoot: Path = Paths.get("").toAbsolutePath().normalize()

    @Test
    fun semanticProvider目录不得再依赖legacyExtract与旧图模型() {
        val violations = scanForbiddenImports(
            relativeDirectory = "src/main/kotlin/com/charmnight/linkgraph/semantic/provider",
            forbiddenPatterns = listOf(
                Regex("""^import\s+com\.charmnight\.linkgraph\.extract\."""),
                Regex("""^import\s+com\.charmnight\.linkgraph\.model\.GraphNode$"""),
                Regex("""^import\s+com\.charmnight\.linkgraph\.model\.GraphEdge$"""),
                Regex("""^import\s+com\.charmnight\.linkgraph\.model\.GraphDocument$"""),
            ),
        )

        assertNoViolations(
            title = "semantic/provider 仍存在禁止依赖",
            violations = violations,
        )
    }

    @Test
    fun semanticGraph目录不得依赖legacyExtract且不得继续承担流程猜测职责() {
        val importViolations = scanForbiddenImports(
            relativeDirectory = "src/main/kotlin/com/charmnight/linkgraph/semantic/graph",
            forbiddenPatterns = listOf(
                Regex("""^import\s+com\.charmnight\.linkgraph\.extract\."""),
            ),
        )
        val implementationViolations = scanForbiddenSourceFragments(
            relativeFile = "src/main/kotlin/com/charmnight/linkgraph/semantic/graph/GraphAssembler.kt",
            forbiddenFragments = listOf(
                "projectTransparentFlowRelations(",
                "applyImplicitDecisionBranchLabels(",
                "syntheticFlowRelation(",
                "relation.copy(kind = SemanticRelationKind.CONTROL_FLOW)",
            ),
        )

        assertNoViolations(
            title = "semantic/graph 仍残留流程猜测逻辑",
            violations = importViolations + implementationViolations,
        )
    }

    @Test
    fun linkGraphProjectService语义主路径不得直接依赖JavaResolver() {
        val violations = scanForbiddenSourceFragments(
            relativeFile = "src/main/kotlin/com/charmnight/linkgraph/services/LinkGraphProjectService.kt",
            forbiddenFragments = listOf(
                "import com.charmnight.linkgraph.extract.JavaResolver",
                "testJavaResolverOverride",
                "defaultJavaResolver",
                "private val javaResolver: JavaResolver",
            ),
        )

        assertNoViolations(
            title = "LinkGraphProjectService 主路径仍直接依赖 JavaResolver",
            violations = violations,
        )
    }

    private fun scanForbiddenImports(
        relativeDirectory: String,
        forbiddenPatterns: List<Regex>,
    ): List<String> {
        return kotlinFiles(relativeDirectory).flatMap { file ->
            file.readText()
                .lineSequence()
                .mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    val matched = forbiddenPatterns.firstOrNull { pattern -> pattern.containsMatchIn(trimmed) }
                    if (matched == null) {
                        null
                    } else {
                        "${projectRelativePath(file)}:${index + 1}: $trimmed"
                    }
                }
                .toList()
        }
    }

    private fun scanForbiddenSourceFragments(
        relativeFile: String,
        forbiddenFragments: List<String>,
    ): List<String> {
        val file = projectRoot.resolve(relativeFile)
        val content = file.readText()
        return forbiddenFragments.mapNotNull { fragment ->
            val lineIndex = content.lineSequence().indexOfFirst { line -> line.contains(fragment) }
            if (lineIndex < 0) {
                null
            } else {
                "${projectRelativePath(file)}:${lineIndex + 1}: 命中禁止片段 `$fragment`"
            }
        }
    }

    private fun kotlinFiles(relativeDirectory: String): List<Path> {
        val directory = projectRoot.resolve(relativeDirectory)
        Files.walk(directory).use { stream ->
            return stream
                .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    private fun assertNoViolations(
        title: String,
        violations: List<String>,
    ) {
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(title)
                violations.forEach(::appendLine)
            },
        )
    }

    private fun projectRelativePath(path: Path): String {
        return projectRoot.relativize(path.toAbsolutePath().normalize()).toString()
    }
}
