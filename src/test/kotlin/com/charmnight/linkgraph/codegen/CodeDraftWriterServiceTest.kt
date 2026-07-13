package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.source.SourceArchiveReadLimits
import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
class CodeDraftWriterServiceTest {
    @Test
    fun writesOperationOnlyCreateFilePayloadInsteadOfEmptyContent() {
        val projectDir = createTempDirectory("link-graph-writer-create-operation-test")
        val targetPath = "src/main/java/com/example/Created.java"
        val expectedContent = "package com.example; class Created {}"
        val draft = RemoteCodeGenerationResultParser.parse(
            content = """
                {
                  "warnings": [],
                  "drafts": [
                    {
                      "id": "draft:create-operation",
                      "sourceNodeId": "class:created",
                      "title": "Created.java",
                      "targetPath": "$targetPath",
                      "content": null,
                      "editOperations": [
                        {
                          "operationId": "op:create-file",
                          "filePath": "$targetPath",
                          "scopeId": null,
                          "kind": "CREATE_FILE",
                          "payload": "$expectedContent",
                          "warnings": []
                        }
                      ],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        ).drafts.single()

        val report = CodeDraftWriterService().writeDrafts(projectDir.toString(), listOf(draft))

        assertIs<CodeDraftCommand.CreateFile>(draft.command)
        assertEquals(expectedContent, Files.readString(projectDir.resolve(targetPath)))
        assertEquals(listOf(targetPath), report.writtenFiles)
        assertTrue(report.skippedFiles.isEmpty())
    }

    @Test
    fun rejectsNewDraftWriteThroughSymlinkedProjectDirectory() {
        val projectDir = createTempDirectory("link-graph-writer-symlink-root-test")
        val outsideDir = createTempDirectory("link-graph-writer-symlink-outside-test")
        val linkDir = projectDir.resolve("generated")
        Files.createSymbolicLink(linkDir, outsideDir)
        val draft = GeneratedCodeDraft.createFile(
            id = "draft:symlink-escape",
            sourceNodeId = "class:symlink-escape",
            title = "Leak.java",
            targetPath = "generated/Leak.java",
            content = "package generated; class Leak {}",
        )

        val report = CodeDraftWriterService().writeDrafts(projectDir.toString(), listOf(draft))

        assertTrue(report.skippedFiles.contains(draft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains("项目目录之外") })
        assertFalse(Files.exists(outsideDir.resolve("Leak.java")))
    }

    @Test
    fun rejectsExistingJavaFileWriteWithoutValidatedScope() {
        val projectDir = createTempDirectory("link-graph-writer-test")
        val targetFile = projectDir.resolve("src/main/java/com/example/OrderService.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class OrderService {
                    public void existing() {
                    }
                }
            """.trimIndent(),
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft.createFile(
                    id = "draft:method:order-service-place-draft",
                    sourceNodeId = "method:order-service-place-draft",
                    title = "OrderService.java",
                    targetPath = "src/main/java/com/example/OrderService.java",
                    content = """
                        package com.example;

                        public class OrderService {

                            public void placeDraft(String arg0) {
                                throw new UnsupportedOperationException("由链路图生成");
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertFalse(report.writtenFiles.contains("src/main/java/com/example/OrderService.java"))
        assertTrue(report.skippedFiles.contains("src/main/java/com/example/OrderService.java"))
        assertTrue(report.warnings.any { it.contains("CREATE_FILE") })
        assertTrue(written.contains("public void existing()"))
        assertFalse(written.contains("public void placeDraft(String arg0)"))
    }

    @Test
    fun skipsOversizedExistingFileBeforeComparingDraftContent() {
        val projectDir = createTempDirectory("link-graph-writer-oversized-existing-test")
        val targetFile = projectDir.resolve("src/main/java/com/example/Huge.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(targetFile, "x".repeat(SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES + 1))
        val draft = GeneratedCodeDraft.createFile(
            id = "draft:huge",
            sourceNodeId = "class:huge",
            title = "Huge.java",
            targetPath = "src/main/java/com/example/Huge.java",
            content = "package com.example; class Huge {}",
        )

        val report = CodeDraftWriterService().writeDrafts(projectDir.toString(), listOf(draft))

        assertTrue(report.skippedFiles.contains(draft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains("过大") })
    }

    @Test
    fun skipsOversizedNewDraftBeforeWritingContent() {
        val projectDir = createTempDirectory("link-graph-writer-oversized-new-test")
        val draft = GeneratedCodeDraft.createFile(
            id = "draft:huge-new",
            sourceNodeId = "class:huge-new",
            title = "HugeNew.java",
            targetPath = "src/main/java/com/example/HugeNew.java",
            content = "x".repeat(SourceArchiveReadLimits.MAX_TEXT_ENTRY_BYTES + 1),
        )

        val report = CodeDraftWriterService().writeDrafts(projectDir.toString(), listOf(draft))

        assertTrue(report.skippedFiles.contains(draft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains("过大") })
        assertFalse(Files.exists(projectDir.resolve(draft.targetPath)))
    }

    @Test
    fun rejectsExistingJavaFileMergeWithoutValidatedScope() {
        val projectDir = createTempDirectory("link-graph-writer-merge-test")
        val targetFile = projectDir.resolve("src/main/java/com/example/OrderService.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public class OrderService {
                    public void existing() {
                    }
                }
            """.trimIndent(),
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft.createFile(
                    id = "draft:order-service",
                    sourceNodeId = "class:order-service",
                    title = "OrderService.java",
                    targetPath = "src/main/java/com/example/OrderService.java",
                    content = """
                        package com.example;

                        import java.time.Instant;
                        import java.util.List;

                        /**
                         * 订单服务草稿。
                         */
                        public class OrderService {
                            private final List<String> tags = List.of();

                            public Instant placeDraft(String arg0) {
                                throw new UnsupportedOperationException("由链路图生成");
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertTrue(report.skippedFiles.contains("src/main/java/com/example/OrderService.java"))
        assertTrue(report.warnings.any { it.contains("CREATE_FILE") })
        assertFalse(written.contains("import java.time.Instant;"))
        assertFalse(written.contains("import java.util.List;"))
        assertFalse(written.contains("/**\n * 订单服务草稿。"))
        assertFalse(written.contains("private final List<String> tags = List.of();"))
        assertFalse(written.contains("public Instant placeDraft(String arg0)"))
        assertTrue(written.contains("public void existing()"))
    }

    @Test
    fun treatsIdenticalJavaDraftAsWrittenWhenAppliedTwice() {
        val projectDir = createTempDirectory("link-graph-writer-idempotent-test")
        val draft = GeneratedCodeDraft.createFile(
            id = "draft:order-draft-dto",
            sourceNodeId = "class:order-draft-dto",
            title = "OrderDraftDto.java",
            targetPath = "src/main/java/com/example/OrderDraftDto.java",
            content = """
                package com.example;

                /**
                 * 草稿 DTO。
                 */
                public class OrderDraftDto {
                }
            """.trimIndent(),
        )

        val service = CodeDraftWriterService()
        val firstReport = service.writeDrafts(projectBasePath = projectDir.toString(), drafts = listOf(draft))
        val secondReport = service.writeDrafts(projectBasePath = projectDir.toString(), drafts = listOf(draft))

        assertTrue(firstReport.writtenFiles.contains(draft.targetPath))
        assertTrue(secondReport.writtenFiles.contains(draft.targetPath))
        assertFalse(secondReport.skippedFiles.contains(draft.targetPath))
    }

    @Test
    fun skipsSingleDraftWhenFilesystemWriteFailsAndContinuesBatch() {
        val projectDir = createTempDirectory("link-graph-writer-fs-failure-test")
        Files.writeString(projectDir.resolve("src"), "not a directory")
        val brokenDraft = GeneratedCodeDraft.createFile(
            id = "draft:broken",
            sourceNodeId = "class:broken",
            title = "Broken.java",
            targetPath = "src/main/java/com/example/Broken.java",
            content = "package com.example; class Broken {}",
        )
        val okDraft = GeneratedCodeDraft.createFile(
            id = "draft:ok",
            sourceNodeId = "class:ok",
            title = "Ok.java",
            targetPath = "generated/Ok.java",
            content = "package generated; class Ok {}",
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(brokenDraft, okDraft),
        )

        assertTrue(report.skippedFiles.contains(brokenDraft.targetPath))
        assertTrue(report.writtenFiles.contains(okDraft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains(brokenDraft.targetPath) })
        assertTrue(Files.exists(projectDir.resolve(okDraft.targetPath)))
    }

    @Test
    fun skipsSingleDraftWhenTargetPathCannotBeResolvedAndContinuesBatch() {
        val projectDir = createTempDirectory("link-graph-writer-invalid-path-test")
        val brokenDraft = GeneratedCodeDraft.createFile(
            id = "draft:broken-path",
            sourceNodeId = "class:broken-path",
            title = "BrokenPath.java",
            targetPath = "src/main/java/com/example/\u0000/BrokenPath.java",
            content = "package com.example; class BrokenPath {}",
        )
        val okDraft = GeneratedCodeDraft.createFile(
            id = "draft:ok-path",
            sourceNodeId = "class:ok-path",
            title = "OkPath.java",
            targetPath = "generated/OkPath.java",
            content = "package generated; class OkPath {}",
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(brokenDraft, okDraft),
        )

        assertTrue(report.skippedFiles.contains(brokenDraft.targetPath))
        assertTrue(report.writtenFiles.contains(okDraft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains(brokenDraft.targetPath) })
        assertTrue(Files.exists(projectDir.resolve(okDraft.targetPath)))
    }

    @Test
    fun returnsWarningWhenProjectBasePathIsAFile() {
        val projectBaseFile = createTempDirectory("link-graph-writer-file-root-test").resolve("project-root")
        Files.writeString(projectBaseFile, "not a directory")
        val draft = GeneratedCodeDraft.createFile(
            id = "draft:new-file",
            sourceNodeId = "class:new-file",
            title = "NewFile.java",
            targetPath = "src/main/java/com/example/NewFile.java",
            content = "package com.example; class NewFile {}",
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectBaseFile.toString(),
            drafts = listOf(draft),
        )

        assertTrue(report.skippedFiles.contains(draft.targetPath))
        assertTrue(report.warnings.any { warning -> warning.contains(draft.targetPath) })
    }

    @Test
    fun blocksExistingMethodReplacementWithoutValidatedScope() {
        val projectDir = createTempDirectory("link-graph-writer-replace-method-test")
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

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft.createFile(
                    id = "draft:method:file-download",
                    sourceNodeId = "method:file-download",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    content = """
                        package com.example;

                        public class CommonController {
                            public String fileDownload(String baseUrl) {
                                if (baseUrl.startsWith("/usr")) {
                                    return baseUrl.replaceFirst("/usr", "/tmp");
                                }
                                if (baseUrl.startsWith("C:/")) {
                                    throw new IllegalArgumentException("windows not supported");
                                }
                                return baseUrl;
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertFalse(report.writtenFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(report.skippedFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(report.warnings.any { it.contains("CREATE_FILE") })
        assertFalse(written.contains("baseUrl.startsWith(\"/usr\")"))
        assertFalse(written.contains("windows not supported"))
        assertTrue(written.contains("return baseUrl;"))
    }

    @Test
    fun skipsMergingClassStyleMethodDraftIntoExistingInterfaceFile() {
        val projectDir = createTempDirectory("link-graph-writer-interface-test")
        val targetFile = projectDir.resolve("src/main/java/com/example/OrderGateway.java")
        Files.createDirectories(targetFile.parent)
        Files.writeString(
            targetFile,
            """
                package com.example;

                public interface OrderGateway {
                }
            """.trimIndent(),
        )

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft.createFile(
                    id = "draft:order-gateway",
                    sourceNodeId = "method:order-gateway-place",
                    title = "OrderGateway.java",
                    targetPath = "src/main/java/com/example/OrderGateway.java",
                    content = """
                        package com.example;

                        public class OrderGateway {

                            public void placeDraft(String arg0) {
                                throw new UnsupportedOperationException("由链路图生成");
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(report.skippedFiles.contains("src/main/java/com/example/OrderGateway.java"))
        assertEquals(
            """
                package com.example;

                public interface OrderGateway {
                }
            """.trimIndent(),
            Files.readString(targetFile).trim(),
        )
    }

    @Test
    fun blocksOverreachOnExistingJavaFileWhenNoValidatedScopeIsAttached() {
        val projectDir = createTempDirectory("link-graph-writer-overreach-test")
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

        val report = CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft.createFile(
                    id = "draft:method:upload-file",
                    sourceNodeId = "method:upload-file",
                    title = "CommonController.java",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    content = """
                        package com.example;

                        public class CommonController {
                            public String download(String resource) {
                                return resource + "-changed";
                            }

                            public String uploadFile(String fileName) {
                                return fileName.trim();
                            }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val written = Files.readString(targetFile)
        assertFalse(report.writtenFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(report.skippedFiles.contains("src/main/java/com/example/CommonController.java"))
        assertTrue(report.warnings.any { it.contains("CREATE_FILE") })
        assertFalse(written.contains("""return resource + "-changed";"""))
        assertFalse(written.contains("return fileName.trim();"))
        assertTrue(written.contains("return resource;"))
        assertTrue(written.contains("return fileName;"))
    }
}
