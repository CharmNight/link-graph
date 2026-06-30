package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.agent.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.agent.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.charmnight.linkgraph.source.SourceContentAccessPolicy
import com.intellij.openapi.application.ApplicationManager
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
        val snapshot = testSnapshot(
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
        ).toToolGraphSnapshot()
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

    fun testReadsMethodBodyWhenInvocationCallsiteSharesSameSymbolSignature() {
        val basePath = Path.of(requireNotNull(project.basePath))
        val controllerFile = basePath.resolve("src/main/java/com/example/FileUploadController.java")
        val serviceFile = basePath.resolve("src/main/java/com/example/FileUploadServiceImpl.java")
        Files.createDirectories(controllerFile.parent)
        Files.writeString(
            controllerFile,
            """
            class FileUploadController {
                boolean upload() {
                    return fileUploadService.uploadFile();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            serviceFile,
            """
            class FileUploadServiceImpl {
                boolean uploadFile() {
                    return sshFileUploadUtil.uploadFileViaSCP();
                }
            }
            """.trimIndent(),
        )
        val targetSignature = "com.example.FileUploadServiceImpl.uploadFile():boolean"
        val snapshot = testSnapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "invoke:controller-to-service",
                        type = NodeType.FLOW_ACTION,
                        title = "调用 FileUploadServiceImpl.uploadFile",
                        signature = targetSignature,
                        metadata = mapOf(
                            "flow.kind" to "INVOCATION",
                            "source.filePath" to controllerFile.toString(),
                            "source.startLine" to "2",
                            "source.endLine" to "4",
                        ),
                    ),
                    GraphNode(
                        id = "method:file-upload-service-impl-upload-file",
                        type = NodeType.METHOD,
                        title = "FileUploadServiceImpl.uploadFile",
                        signature = targetSignature,
                        metadata = mapOf(
                            "source.filePath" to serviceFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "5",
                        ),
                    ),
                ),
            ),
        ).toToolGraphSnapshot()
        val tool = ReadSymbolTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf("symbolSignature" to targetSignature),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val sourceSnippet = requireNotNull(result.payload["sourceSnippetContext"] as? SourceSnippetContext)
        assertEquals(serviceFile.toString(), sourceSnippet.filePath)
        assertTrue(sourceSnippet.snippet?.contains("sshFileUploadUtil.uploadFileViaSCP()") == true)
        assertFalse(sourceSnippet.snippet?.contains("fileUploadService.uploadFile()") == true)
    }

    fun testReadsSymbolFromArchitectureIndexBeforeGraphAnchor() {
        myFixture.addFileToProject(
            "src/main/java/com/example/IndexedFirstService.java",
            """
            package com.example;

            public class IndexedFirstService {
                public String target() {
                    return "from-index";
                }
            }
            """.trimIndent(),
        )
        val staleAnchorFile = Path.of(requireNotNull(project.basePath))
            .resolve("src/main/java/com/example/StaleAnchor.java")
        Files.createDirectories(staleAnchorFile.parent)
        Files.writeString(
            staleAnchorFile,
            """
            package com.example;
            class StaleAnchor {
                String target() { return "from-stale-anchor"; }
            }
            """.trimIndent(),
        )
        val signature = "com.example.IndexedFirstService.target():java.lang.String"
        val snapshot = testSnapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:stale",
                        type = NodeType.METHOD,
                        title = "IndexedFirstService.target",
                        signature = signature,
                        metadata = mapOf(
                            "source.filePath" to staleAnchorFile.toString(),
                            "source.startLine" to "1",
                            "source.endLine" to "4",
                        ),
                    ),
                ),
            ),
        ).toToolGraphSnapshot()
        val tool = ReadSymbolTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf("symbolSignature" to signature),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val sourceSnippet = requireNotNull(result.payload["sourceSnippetContext"] as? SourceSnippetContext)
        assertTrue(sourceSnippet.filePath.endsWith("IndexedFirstService.java"), sourceSnippet.filePath)
        assertTrue(sourceSnippet.snippet?.contains("from-index") == true)
        assertFalse(sourceSnippet.snippet?.contains("from-stale-anchor") == true)
        assertTrue((result.payload["symbolId"] as? String).orEmpty().startsWith("jvm:method:"))
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
        val snapshot = testSnapshot(
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
        ).toToolGraphSnapshot()
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

    fun testReadsClassByQualifiedNameWhenGraphAnchorIsMissing() {
        myFixture.addFileToProject(
            "src/main/java/com/example/QualifiedNameOnlyService.java",
            """
            package com.example;

            public class QualifiedNameOnlyService {
                public String name() {
                    return "ok";
                }
            }
            """.trimIndent(),
        )
        val tool = ReadSymbolTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf("symbolSignature" to "com.example.QualifiedNameOnlyService"),
            context = ToolExecutionContext(
                project = project,
                snapshot = testSnapshot().toToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val sourceSnippet = requireNotNull(result.payload["sourceSnippetContext"] as? SourceSnippetContext)
        assertTrue(sourceSnippet.snippet?.contains("QualifiedNameOnlyService") == true)
        assertEquals("PROJECT_SOURCE", result.payload["origin"])
        assertEquals(false, result.payload["decompiled"])
    }

    fun testRejectsJdkClassByQualifiedNameWhenJdkExpansionIsDisabled() {
        val settings = ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java)
        val before = settings.snapshot()
        settings.update(before.copy(allowJdkLibraryExpansion = false))
        val tool = ReadSymbolTool(CodeReadToolFacade())

        try {
            val result = tool.invoke(
                input = mapOf("symbolSignature" to "java.lang.String"),
                context = ToolExecutionContext(
                    project = project,
                    snapshot = testSnapshot().toToolGraphSnapshot(),
                    artifactStore = InMemoryArtifactStore(),
                    runBudget = RunBudget(),
                ),
            )

            assertFalse(result.success)
            assertEquals("未读取到 symbol 对应源码", result.errorMessage)
            assertEquals("JDK_SOURCE_ACCESS_DISABLED", result.payload["sourceUnavailableReason"])
        } finally {
            settings.update(before)
        }
    }

    fun testIdeResolverRejectsJdkClassWhenAccessPolicyDisallowsJdk() {
        val resolver = IdeSourceContentResolver(
            project,
            SourceContentAccessPolicy(allowExternalLibraries = true, allowJdk = false),
        )

        assertEquals(null, resolver.readClassByQualifiedName("java.lang.String"))
        assertEquals(null, resolver.readByVirtualFileUrl("jrt://${System.getProperty("java.home")}!/java.base/java/lang/String.class"))
    }

    fun testReadsJdkClassByQualifiedNameWhenJdkExpansionIsEnabled() {
        val settings = ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java)
        val before = settings.snapshot()
        settings.update(before.copy(allowJdkLibraryExpansion = true))
        val tool = ReadSymbolTool(CodeReadToolFacade())

        try {
            val result = tool.invoke(
                input = mapOf("symbolSignature" to "java.lang.String"),
                context = ToolExecutionContext(
                    project = project,
                    snapshot = testSnapshot().toToolGraphSnapshot(),
                    artifactStore = InMemoryArtifactStore(),
                    runBudget = RunBudget(),
                ),
            )

            assertTrue(result.success)
            val sourceSnippet = requireNotNull(result.payload["sourceSnippetContext"] as? SourceSnippetContext)
            assertTrue(sourceSnippet.snippet?.contains("String") == true)
            assertTrue(result.payload["origin"] in setOf("JDK_SOURCE", "JDK_CLASS", "LIBRARY_SOURCE_JAR", "LIBRARY_CLASS_JAR"))
            assertNotNull(result.payload["virtualFileUrl"])
        } finally {
            settings.update(before)
        }
    }
}
