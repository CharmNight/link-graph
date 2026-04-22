package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeDraftWriterServicePlatformTest : BasePlatformTestCase() {
    fun testPrepareExistingFilePreviewWithBeforeAfterSnippets() {
        val projectDir = createTempDirectory("link-graph-writer-preview-test")
        val targetFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public String fileDownload(String baseUrl) {
                        return baseUrl;
                    }
                }
            """.trimIndent(),
        )

        val result = CodeDraftWriterService(project).prepareExistingFileDraft(
            projectBasePath = projectDir.toString(),
            draft = GeneratedCodeDraft(
                id = "draft:file-download",
                sourceNodeId = "method:file-download",
                title = "CommonController.java",
                targetPath = "src/main/java/com/example/CommonController.java",
                editOperations = listOf(
                    CodeEditOperation(
                        operationId = "op-file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        scopeId = "scope-file-download",
                        kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                        payload = """{
                            if (baseUrl == null || baseUrl.isBlank()) {
                                throw new IllegalArgumentException("baseUrl");
                            }
                            return baseUrl.trim();
                        }""".trimIndent(),
                    ),
                ),
                editScopes = listOf(
                    EditScope(
                        scopeId = "scope-file-download",
                        targetNodeId = "method:file-download",
                        filePath = "src/main/java/com/example/CommonController.java",
                        language = "JAVA",
                        symbolKind = "METHOD",
                        symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                        startLine = 4,
                        endLine = 6,
                        allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                        supportingFindingIds = listOf("finding-file-download"),
                    ),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertEquals(1, result.preparedEdits.size)
        assertTrue(result.preparedEdits.single().beforeText.contains("return baseUrl;"))
        assertTrue(result.preparedEdits.single().afterText.contains("return baseUrl.trim();"))
        assertTrue(result.previewText.contains("return baseUrl.trim();"))
    }

    fun testPrepareExistingFilePreviewMatchesScopedJavaMethodWithImportedServletTypes() {
        val projectDir = createTempDirectory("link-graph-writer-servlet-preview-test")
        val targetFile = projectDir.resolve("src/main/java/com/ruoyi/web/controller/common/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.ruoyi.web.controller.common;

                import javax.servlet.http.HttpServletRequest;
                import javax.servlet.http.HttpServletResponse;

                public class CommonController {
                    public void fileDownload(
                        String fileName,
                        Boolean delete,
                        HttpServletResponse response,
                        HttpServletRequest request
                    ) {
                        response.setContentType("application/octet-stream");
                    }
                }
            """.trimIndent(),
        )

        val result = CodeDraftWriterService(project).prepareExistingFileDraft(
            projectBasePath = projectDir.toString(),
            draft = GeneratedCodeDraft(
                id = "draft:file-download-servlet",
                sourceNodeId = "method:file-download-servlet",
                title = "CommonController.java",
                targetPath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                editOperations = listOf(
                    CodeEditOperation(
                        operationId = "op-file-download-servlet",
                        filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                        scopeId = "scope-file-download-servlet",
                        kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                        payload = """{
                            if (Boolean.TRUE.equals(delete)) {
                                response.setContentType("application/octet-stream");
                            }
                        }""".trimIndent(),
                    ),
                ),
                editScopes = listOf(
                    EditScope(
                        scopeId = "scope-file-download-servlet",
                        targetNodeId = "method:file-download-servlet",
                        filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                        language = "JAVA",
                        symbolKind = "METHOD",
                        symbolSignature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean,javax.servlet.http.HttpServletResponse,javax.servlet.http.HttpServletRequest):void",
                        startLine = 6,
                        endLine = 13,
                        allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                        supportingFindingIds = listOf("finding-file-download-servlet"),
                    ),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertEquals(1, result.preparedEdits.size)
        assertTrue(result.previewText.contains("Boolean.TRUE.equals(delete)"))
    }

    fun testWriteFlowScopeDraftOnlyChangesDeleteBranch() {
        val projectDir = Files.createDirectories(
            java.nio.file.Path.of(project.basePath!!).resolve("build/test-flow-scope-write-${System.nanoTime()}"),
        )
        val targetFile = projectDir.resolve("src/main/java/com/ruoyi/web/controller/common/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.ruoyi.web.controller.common;

                public class CommonController {
                    public void fileDownload(String fileName, Boolean delete) {
                        String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                        String filePath = "/tmp/" + realFileName;
                        response.setContentType("application/octet-stream");
                        FileUtils.writeBytes(filePath, response.getOutputStream());
                        if (delete) {
                            FileUtils.deleteFile(filePath);
                        }
                        log.error("下载文件失败");
                    }
                }
            """.trimIndent(),
        )

        val draft = GeneratedCodeDraft(
            id = "draft:file-download-delete-guard",
            sourceNodeId = "scope:file-download-if",
            title = "CommonController.java",
            targetPath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    scopeId = "scope-delete-guard",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """{
                        if (Boolean.TRUE.equals(delete)) {
                            FileUtils.deleteFile(filePath);
                        }
                    }""".trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-delete-guard",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 9,
                    endLine = 11,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-guard"),
                ),
            ),
        )

        val report = CodeDraftWriterService(project).writeDrafts(projectDir.toString(), listOf(draft))
        val written = Files.readString(targetFile)

        assertTrue(report.writtenFiles.contains(draft.targetPath), report.warnings.joinToString(" | "))
        assertTrue(written.contains("String realFileName = System.currentTimeMillis()"))
        assertTrue(written.contains("FileUtils.writeBytes(filePath, response.getOutputStream());"))
        assertTrue(written.contains("log.error(\"下载文件失败\");"))
        assertTrue(written.contains("if (Boolean.TRUE.equals(delete))"))
    }

    fun testWriteTryScopedDraftPreservesCatchClauseAndSiblingMethods() {
        val projectDir = Files.createDirectories(
            java.nio.file.Path.of(project.basePath!!).resolve("build/test-try-scope-write-${System.nanoTime()}"),
        )
        val targetFile = projectDir.resolve("src/main/java/com/ruoyi/web/controller/common/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.ruoyi.web.controller.common;

                public class CommonController {
                    public void fileDownload(String fileName, Boolean delete) {
                        try {
                            if (!FileUtils.checkAllowDownload(fileName)) {
                                throw new Exception("bad file name");
                            }
                            String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                            String filePath = "/tmp/" + realFileName;
                            FileUtils.writeBytes(filePath, response.getOutputStream());
                            if (delete) {
                                FileUtils.deleteFile(filePath);
                            }
                        } catch (Exception e) {
                            log.error("下载文件失败", e);
                        }
                    }

                    public String uploadFile(String fileName) {
                        return fileName;
                    }
                }
            """.trimIndent(),
        )

        val draft = GeneratedCodeDraft(
            id = "draft:file-download-try-guard",
            sourceNodeId = "scope:file-download-try",
            title = "CommonController.java",
            targetPath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-file-download-try-guard",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    scopeId = "scope-file-download-try",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        if (!FileUtils.checkAllowDownload(fileName)) {
                            throw new Exception("bad file name");
                        }
                        String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                        String filePath = "/tmp/" + realFileName;
                        FileUtils.writeBytes(filePath, response.getOutputStream());
                        if (Boolean.TRUE.equals(delete) && Files.exists(Path.of(filePath))) {
                            FileUtils.deleteFile(filePath);
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-file-download-try",
                    targetNodeId = "scope:file-download-try",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 5,
                    endLine = 14,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-file-download-try"),
                ),
            ),
        )

        val report = CodeDraftWriterService(project).writeDrafts(projectDir.toString(), listOf(draft))
        val written = Files.readString(targetFile)

        assertTrue(report.writtenFiles.contains(draft.targetPath), report.warnings.joinToString(" | "))
        assertTrue(written.contains("try {"))
        assertTrue(written.contains("catch (Exception e)"))
        assertTrue(written.contains("Boolean.TRUE.equals(delete) && Files.exists(Path.of(filePath))"))
        assertTrue(written.contains("public String uploadFile(String fileName)"))
    }

    fun testWriteSingleDraftFromPooledThreadDoesNotTripIdeaReadAccessAssertion() {
        val projectDir = Files.createDirectories(
            java.nio.file.Path.of(project.basePath!!).resolve("build/test-pooled-thread-write-${System.nanoTime()}"),
        )
        val targetFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public String fileDownload(String baseUrl) {
                        return baseUrl;
                    }
                }
            """.trimIndent(),
        )

        val draft = GeneratedCodeDraft(
            id = "draft:file-download",
            sourceNodeId = "method:file-download",
            title = "CommonController.java",
            targetPath = "src/main/java/com/example/CommonController.java",
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-file-download",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-file-download",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """{
                        return baseUrl.trim();
                    }""".trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-file-download",
                    targetNodeId = "method:file-download",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "METHOD",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                    startLine = 4,
                    endLine = 6,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-file-download"),
                ),
            ),
        )

        val future = ApplicationManager.getApplication().executeOnPooledThread<GeneratedCodeDraftWriteReport> {
            CodeDraftWriterService(project).writeDrafts(projectDir.toString(), listOf(draft))
        }
        repeat(100) {
            if (future.isDone) {
                return@repeat
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val report = future.get()
        val written = Files.readString(targetFile)

        assertTrue(report.writtenFiles.contains(draft.targetPath), report.warnings.joinToString(" | "))
        assertTrue(written.contains("return baseUrl.trim();"))
    }

    fun testWriteDraftUsesCurrentUnsavedEditorDocumentAsPatchBaseline() {
        val projectDir = Files.createDirectories(
            java.nio.file.Path.of(project.basePath!!).resolve("build/test-unsaved-document-baseline-${System.nanoTime()}"),
        )
        val targetFile = projectDir.resolve("src/main/java/com/example/CommonController.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class CommonController {
                    public String fileDownload(String baseUrl) {
                        return baseUrl;
                    }
                }
            """.trimIndent(),
        )

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile)
            ?: error("virtual file not found")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: error("document not found")
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.indexOf("public class CommonController {"), "    // unsaved change\n")
        }

        val draft = GeneratedCodeDraft(
            id = "draft:file-download-unsaved",
            sourceNodeId = "method:file-download",
            title = "CommonController.java",
            targetPath = "src/main/java/com/example/CommonController.java",
            editOperations = listOf(
                CodeEditOperation(
                    operationId = "op-file-download-unsaved",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-file-download-unsaved",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """{
                        return baseUrl.trim();
                    }""".trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-file-download-unsaved",
                    targetNodeId = "method:file-download",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "METHOD",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String):java.lang.String",
                    startLine = 4,
                    endLine = 6,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-file-download-unsaved"),
                ),
            ),
        )

        val report = CodeDraftWriterService(project).writeDrafts(projectDir.toString(), listOf(draft))
        val written = Files.readString(targetFile)

        assertTrue(report.writtenFiles.contains(draft.targetPath), report.warnings.joinToString(" | "))
        assertTrue(written.contains("// unsaved change"), written)
        assertTrue(written.contains("return baseUrl.trim();"), written)
    }
}
