package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadSymbolToolTest : BasePlatformTestCase() {
    fun testReadsSymbolSnippetFromGraphAnchor() {
        val sourceFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/ReadSymbolOrderService.java")
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
        val snapshot = GraphEditorStateService.Snapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit",
                        type = NodeType.METHOD,
                        title = "OrderService.submit",
                        signature = "com.example.OrderService.submit(java.lang.String):java.lang.String",
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
        )
        val tool = ReadSymbolTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "symbolSignature" to "com.example.OrderService.submit(java.lang.String):java.lang.String",
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val sourceSnippet = requireNotNull(result.payload["sourceSnippetContext"] as? SourceSnippetContext)
        assertEquals(sourceFile.toString(), sourceSnippet.filePath)
        assertTrue(sourceSnippet.snippet?.contains("fallback(request)") == true)
    }

    fun testRejectsSymbolSnippetWhenAnchorPointsOutsideProjectRoot() {
        val sourceFile = Files.createTempFile("read-symbol-external", ".java")
        Files.writeString(
            sourceFile,
            """
            class ExternalOrderService {
                String submit(String request) {
                    return fallback(request);
                }
            }
            """.trimIndent(),
        )
        val snapshot = GraphEditorStateService.Snapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit",
                        type = NodeType.METHOD,
                        title = "OrderService.submit",
                        signature = "com.example.OrderService.submit(java.lang.String):java.lang.String",
                        metadata = mapOf(
                            "source.filePath" to sourceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
        )
        val tool = ReadSymbolTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "symbolSignature" to "com.example.OrderService.submit(java.lang.String):java.lang.String",
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertFalse(result.success)
        assertEquals("未读取到 symbol 对应源码", result.errorMessage)
    }
}
