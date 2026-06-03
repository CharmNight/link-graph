package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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
                snapshot = testSnapshot().toToolGraphSnapshot(),
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
                snapshot = testSnapshot().toToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val snippet = result.payload["snippet"]?.toString().orEmpty()
        assertTrue(snippet.contains("submit"))
        assertTrue(snippet.contains("fallback(request)"))
    }

    fun testReadsAbsolutePathFromAdditionalProjectContentRoot() {
        val contentRoot = Files.createTempDirectory("read-source-snippet-content-root")
        val sourceFile = contentRoot.resolve("src/main/java/com/example/ScheduledJob.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            import org.springframework.scheduling.annotation.Scheduled;

            class ScheduledJob {
                @Scheduled(cron = "0 15,45 * * * ?")
                void updateServiceResource() {
                    resourceService.isPeriodicUpdates();
                }
            }
            """.trimIndent(),
        )
        val contentRootFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot))
        PsiTestUtil.addContentRoot(module, contentRootFile)
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        val result = tool.invoke(
            input = mapOf(
                "filePath" to sourceFile.toString(),
                "startLine" to 4,
                "endLine" to 6,
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = testSnapshot().toToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val snippet = result.payload["snippet"]?.toString().orEmpty()
        assertTrue(result.success, result.errorMessage ?: "expected source read to succeed")
        assertTrue(snippet.contains("@Scheduled"))
        assertTrue(snippet.contains("resourceService.isPeriodicUpdates()"))
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
                snapshot = testSnapshot().toToolGraphSnapshot(),
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
                snapshot = testSnapshot().toToolGraphSnapshot(),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertFalse(result.success)
        assertEquals("未读取到源码片段", result.errorMessage)
    }

    fun testReadsAttachedJarBangPathThroughResolverWithoutScheme() {
        val settings = ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java)
        val before = settings.snapshot()
        val jarDir = Files.createTempDirectory("read-source-snippet-attached-jar")
        val sourceJar = jarDir.resolve("external-sources.jar")
        writeJar(
            sourceJar,
            mapOf(
                "com/example/ExternalService.java" to
                    """
                    package com.example;
                    public class ExternalService {
                        public String name() {
                            return "attached";
                        }
                    }
                    """.trimIndent().toByteArray(),
            ),
        )
        settings.update(
            before.copy(
                attachedJars = listOf(
                    AttachedJarEntry(path = sourceJar.toString(), sourceJarPath = sourceJar.toString()),
                ),
            ),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        try {
            val result = tool.invoke(
                input = mapOf(
                    "filePath" to "$sourceJar!/com/example/ExternalService.java",
                    "startLine" to 2,
                    "endLine" to 4,
                ),
                context = ToolExecutionContext(
                    project = project,
                    snapshot = testSnapshot().toToolGraphSnapshot(),
                    artifactStore = InMemoryArtifactStore(),
                    runBudget = RunBudget(),
                ),
            )

            assertTrue(result.success, result.errorMessage ?: "expected attached jar snippet")
            assertEquals("USER_ATTACHED_SOURCE_JAR", result.payload["origin"])
            assertTrue(result.payload["snippet"].toString().contains("ExternalService"))
            assertTrue(result.payload["virtualFileUrl"].toString().startsWith("jar://"))
        } finally {
            settings.update(before)
        }
    }

    fun testReportsAttachedClassJarDecompileDisabledForSnippetRead() {
        val settings = ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java)
        val before = settings.snapshot()
        val jarDir = Files.createTempDirectory("read-source-snippet-attached-class-jar")
        val classJar = jarDir.resolve("external.jar")
        writeJar(classJar, mapOf("com/example/ExternalService.class" to byteArrayOf(0)))
        settings.update(
            before.copy(
                attachedJars = listOf(AttachedJarEntry(path = classJar.toString())),
                allowClassJarDecompile = false,
            ),
        )
        val tool = ReadSourceSnippetTool(CodeReadToolFacade())

        try {
            val result = tool.invoke(
                input = mapOf(
                    "filePath" to "$classJar!/com/example/ExternalService.class",
                    "startLine" to 1,
                    "endLine" to 3,
                ),
                context = ToolExecutionContext(
                    project = project,
                    snapshot = testSnapshot().toToolGraphSnapshot(),
                    artifactStore = InMemoryArtifactStore(),
                    runBudget = RunBudget(),
                ),
            )

            assertFalse(result.success)
            assertEquals("未读取到源码片段", result.errorMessage)
            assertEquals("CLASS_JAR_DECOMPILE_DISABLED", result.payload["sourceUnavailableReason"])
        } finally {
            settings.update(before)
        }
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}
