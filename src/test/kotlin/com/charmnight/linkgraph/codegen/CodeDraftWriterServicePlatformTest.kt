package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeDraftWriterServicePlatformTest : BasePlatformTestCase() {
    fun testWritesExistingJavaFileViaScopedEditOperations() {
        val projectDir = createTempDirectory("link-graph-writer-platform-java")
        val targetFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )

        val report = CodeDraftWriterService(project).writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft:method:upload-file",
                    sourceNodeId = "method:upload-file",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-upload-file",
                            filePath = "src/main/java/com/example/CommonController.java",
                            scopeId = "scope-upload-file",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                            payload = """
                                public String uploadFile(String fileName) {
                                    if (fileName == null || fileName.isBlank()) {
                                        throw new IllegalArgumentException("fileName");
                                    }
                                    return fileName.trim();
                                }
                            """.trimIndent(),
                        ),
                    ),
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-upload-file",
                            targetNodeId = "method:upload-file",
                            filePath = "src/main/java/com/example/CommonController.java",
                            language = "JAVA",
                            symbolKind = "METHOD",
                            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
                            startLine = 8,
                            endLine = 10,
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            supportingFindingIds = listOf("finding-upload-file"),
                        ),
                    ),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertTrue(report.writtenFiles.contains("src/main/java/com/example/CommonController.java"), report.warnings.joinToString(" | "))
        assertFalse(report.skippedFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(written.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(written.contains("return fileName.trim();"))
        assertTrue(written.contains("return resource;"))
    }

    fun testWritesExistingJavaFileWhenScopeUsesAbsoluteProjectPath() {
        val projectDir = createTempDirectory("link-graph-writer-platform-absolute-scope")
        val targetFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public String download(String resource) {
                        return resource;
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )

        val report = CodeDraftWriterService(project).writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft(
                    id = "draft:method:upload-file-absolute-scope",
                    sourceNodeId = "method:upload-file",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-upload-file-absolute-scope",
                            filePath = "src/main/java/com/example/CommonController.java",
                            scopeId = "scope-upload-file",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                            payload = """
                                public String uploadFile(String fileName) {
                                    if (fileName == null || fileName.isBlank()) {
                                        throw new IllegalArgumentException("fileName");
                                    }
                                    return fileName.trim();
                                }
                            """.trimIndent(),
                        ),
                    ),
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-upload-file",
                            targetNodeId = "method:upload-file",
                            filePath = targetFile.toString(),
                            language = "JAVA",
                            symbolKind = "METHOD",
                            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
                            startLine = 8,
                            endLine = 10,
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                            supportingFindingIds = listOf("finding-upload-file"),
                        ),
                    ),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertTrue(report.writtenFiles.contains("src/main/java/com/example/CommonController.java"), report.warnings.joinToString(" | "))
        assertFalse(report.skippedFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(written.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(written.contains("return fileName.trim();"))
        assertTrue(written.contains("return resource;"))
    }
}
