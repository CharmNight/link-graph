package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadSourceSnippetToolTest : BasePlatformTestCase() {
    fun testReadsRequestedLineRangeFromSourceFile() {
        val sourceFile = Files.createTempFile("read-source-snippet", ".java")
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
        val sourceFile = java.nio.file.Path.of(requireNotNull(project.basePath))
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
}
