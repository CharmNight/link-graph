package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadSourceSnippetToolTest : BasePlatformTestCase() {
    fun testReadsRequestedLineRangeFromSourceFile() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/ReadSourceSnippetOrderService.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class OrderService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "filePath" to sourceFile.toString(),
                "startLine" to 2,
                "endLine" to 3,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val snippet = result.payload["snippet"]?.toString().orEmpty()
        assertTrue(snippet.contains("submit"))
        assertTrue(snippet.contains("fallback(request)"))
        assertEquals(2, result.payload["startLine"])
        assertEquals(3, result.payload["endLine"])
    }

    fun testReadsProjectRelativePathAgainstProjectBasePath() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/OrderService.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            class OrderService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "filePath" to "src/main/java/com/example/OrderService.java",
                "startLine" to 2,
                "endLine" to 3,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val snippet = result.payload["snippet"]?.toString().orEmpty()
        assertTrue(snippet.contains("submit"))
        assertTrue(snippet.contains("fallback(request)"))
    }

    fun testRejectsAbsolutePathOutsideProjectRoot() {
        val externalFile = Files.createTempFile("read-source-snippet-external", ".java")
        Files.writeString(
            externalFile,
            """
            class ExternalOrderService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "filePath" to externalFile.toString(),
                "startLine" to 2,
                "endLine" to 3,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertFalse(result.success)
        assertEquals("未读取到源码片段", result.errorMessage)
    }

    fun testRejectsTraversalPathEscapingProjectRoot() {
        val projectBasePath = Path.of(requireNotNull(project.basePath))
        val externalFile = projectBasePath.parent.resolve("read-source-snippet-escape.java")
        Files.writeString(
            externalFile,
            """
            class EscapedOrderService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "filePath" to "../${externalFile.fileName}",
                "startLine" to 2,
                "endLine" to 3,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertFalse(result.success)
        assertEquals("未读取到源码片段", result.errorMessage)
    }
}
