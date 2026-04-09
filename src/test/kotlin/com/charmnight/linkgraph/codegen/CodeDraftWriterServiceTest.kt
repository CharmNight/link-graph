package com.charmnight.linkgraph.codegen

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeDraftWriterServiceTest {
    @Test
    fun insertsMethodIntoExistingJavaFileInsteadOfSkipping() {
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
                GeneratedCodeDraft(
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
        assertTrue(report.writtenFiles.contains("src/main/java/com/example/OrderService.java"))
        assertFalse(report.skippedFiles.contains("src/main/java/com/example/OrderService.java"))
        assertTrue(written.contains("public void existing()"))
        assertTrue(written.contains("public void placeDraft(String arg0)"))
    }

    @Test
    fun mergesImportsClassDocAndFieldsIntoExistingJavaFile() {
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

        CodeDraftWriterService().writeDrafts(
            projectBasePath = projectDir.toString(),
            drafts = listOf(
                GeneratedCodeDraft(
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
        assertTrue(written.contains("import java.time.Instant;"))
        assertTrue(written.contains("import java.util.List;"))
        assertTrue(written.contains("/**\n * 订单服务草稿。"))
        assertTrue(written.contains("private final List<String> tags = List.of();"))
        assertTrue(written.contains("public Instant placeDraft(String arg0)"))
        assertTrue(written.contains("public void existing()"))
    }

    @Test
    fun treatsIdenticalJavaDraftAsWrittenWhenAppliedTwice() {
        val projectDir = createTempDirectory("link-graph-writer-idempotent-test")
        val draft = GeneratedCodeDraft(
            id = "draft:order-draft-dto",
            sourceNodeId = "class:order-draft-dto",
            title = "OrderDraftDto.java",
            targetPath = "src/main/java/com/example/OrderDraftDto.java",
            content = """
                package com.example;

                /**
                 * Draft DTO.
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
                GeneratedCodeDraft(
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
}
