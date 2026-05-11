package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.application.planning.AuditEvidenceCollector
import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.io.path.createTempDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditEvidenceCollectorTest {
    @Test
    fun `collects source snippets from selected node and direct call target within budget`() {
        val projectDir = createTempDirectory("audit-evidence")
        val controllerFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        val fileUtilsFile = projectDir.resolve("src/main/java/com/example/FileUtils.java")
        Files.createDirectories(controllerFile.parent)
        Files.writeString(
            controllerFile,
            """
                package com.example;

                public class CommonController {
                    public void resourceDownload(String fileName) {
                        FileUtils.checkAllowDownload(fileName);
                    }
                }
            """.trimIndent(),
        )
        Files.writeString(
            fileUtilsFile,
            """
                package com.example;

                public class FileUtils {
                    public static boolean checkAllowDownload(String fileName) {
                        return fileName != null && !fileName.isBlank();
                    }
                }
            """.trimIndent(),
        )

        val collector = AuditEvidenceCollector(maxSnippets = 4, maxTraversalDepth = 1)
        val result = collector.collect(
            graph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:resource-download",
                        type = NodeType.METHOD,
                        title = "CommonController.resourceDownload",
                        signature = "com.example.CommonController.resourceDownload(java.lang.String):void",
                        metadata = mapOf(
                            "source.filePath" to controllerFile.toString(),
                            "source.startOffset" to "0",
                            "source.endOffset" to Files.readString(controllerFile).length.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "7",
                        ),
                    ),
                    GraphNode(
                        id = "method:check-allow-download",
                        type = NodeType.METHOD,
                        title = "FileUtils.checkAllowDownload",
                        signature = "com.example.FileUtils.checkAllowDownload(java.lang.String):boolean",
                        metadata = mapOf(
                            "source.filePath" to fileUtilsFile.toString(),
                            "source.startOffset" to "0",
                            "source.endOffset" to Files.readString(fileUtilsFile).length.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "8",
                        ),
                    ),
                ),
                edges = listOf(
                    GraphEdge(
                        id = "call:download->check",
                        type = EdgeType.CALL,
                        fromNodeId = "method:resource-download",
                        toNodeId = "method:check-allow-download",
                    ),
                ),
            ),
            selectedNodeIds = listOf("method:resource-download"),
        )

        assertEquals(2, result.sourceContext.size)
        assertEquals(2, result.evidenceTrace.size)
        assertTrue(result.sourceContext.any { it.filePath == controllerFile.toString() })
        assertTrue(result.sourceContext.any { it.filePath == fileUtilsFile.toString() })
        assertTrue(result.sourceContext.any { it.snippet?.contains("checkAllowDownload") == true })
    }
}
