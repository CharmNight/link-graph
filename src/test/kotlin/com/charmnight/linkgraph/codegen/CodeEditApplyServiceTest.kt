package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeEditApplyServiceTest : BasePlatformTestCase() {
    fun testJavaReplaceMethodBlockOnlyChangesScopedMethod() {
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).applyToText(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
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
        )

        assertTrue(result.applied, result.warnings.joinToString(" | "))
        assertTrue(result.updatedText.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(result.updatedText.contains("return fileName.trim();"))
        assertTrue(result.updatedText.contains("public String download(String resource) {\n        return resource;\n    }"))
        assertFalse(result.updatedText.contains("return fileName;\n    }"))
    }

    fun testValidationRejectsWhenStructuredEditChangesUnscopedMethod() {
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()
        val after = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource + "-changed";
                }

                public String uploadFile(String fileName) {
                    return fileName.trim();
                }
            }
        """.trimIndent()

        val validation = CodeEditValidationService(project).validateExistingFileRewrite(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            afterText = after,
            allowedScopes = listOf(
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
        )

        assertFalse(validation.isValid)
        assertEquals(
            setOf(
                "com.example.CommonController.download(java.lang.String):java.lang.String",
                "com.example.CommonController.uploadFile(java.lang.String):java.lang.String",
            ),
            validation.changedSymbols,
        )
    }

    fun testKotlinReplaceFunctionBlockOnlyChangesScopedFunction() {
        val before = """
            package com.example

            class CommonController {
                fun download(resource: String): String {
                    return resource
                }

                fun uploadFile(fileName: String): String {
                    return fileName
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).applyToText(
            filePath = "src/main/kotlin/com/example/CommonController.kt",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-upload-file-kt",
                    filePath = "src/main/kotlin/com/example/CommonController.kt",
                    scopeId = "scope-upload-file-kt",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        fun uploadFile(fileName: String): String {
                            require(fileName.isNotBlank()) { "fileName" }
                            return fileName.trim()
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-upload-file-kt",
                    targetNodeId = "method:upload-file-kt",
                    filePath = "src/main/kotlin/com/example/CommonController.kt",
                    language = "KOTLIN",
                    symbolKind = "FUNCTION",
                    symbolSignature = "com.example.CommonController.uploadFile(kotlin.String):kotlin.String",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-upload-file-kt"),
                ),
            ),
        )

        assertTrue(result.applied, result.warnings.joinToString(" | "))
        assertTrue(result.updatedText.contains("""require(fileName.isNotBlank()) { "fileName" }"""))
        assertTrue(result.updatedText.contains("return fileName.trim()"))
        assertTrue(result.updatedText.contains("fun download(resource: String): String {\n        return resource\n    }"))
        assertFalse(result.updatedText.contains("return fileName\n    }"))
    }

    fun testKotlinValidationRejectsWhenStructuredEditChangesUnscopedFunction() {
        val before = """
            package com.example

            class CommonController {
                fun download(resource: String): String {
                    return resource
                }

                fun uploadFile(fileName: String): String {
                    return fileName
                }
            }
        """.trimIndent()
        val after = """
            package com.example

            class CommonController {
                fun download(resource: String): String {
                    return resource + "-changed"
                }

                fun uploadFile(fileName: String): String {
                    return fileName.trim()
                }
            }
        """.trimIndent()

        val validation = CodeEditValidationService(project).validateExistingFileRewrite(
            filePath = "src/main/kotlin/com/example/CommonController.kt",
            beforeText = before,
            afterText = after,
            allowedScopes = listOf(
                EditScope(
                    scopeId = "scope-upload-file-kt",
                    targetNodeId = "method:upload-file-kt",
                    filePath = "src/main/kotlin/com/example/CommonController.kt",
                    language = "KOTLIN",
                    symbolKind = "FUNCTION",
                    symbolSignature = "com.example.CommonController.uploadFile(kotlin.String):kotlin.String",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-upload-file-kt"),
                ),
            ),
        )

        assertFalse(validation.isValid)
        assertEquals(
            setOf(
                "com.example.CommonController.download(kotlin.String):kotlin.String",
                "com.example.CommonController.uploadFile(kotlin.String):kotlin.String",
            ),
            validation.changedSymbols,
        )
    }

    fun testRejectsOperationWhenScopeDoesNotAllowKind() {
        val before = """
            package com.example;

            public class CommonController {
                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).applyToText(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-upload-file-body",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-upload-file",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """{
                        if (fileName == null || fileName.isBlank()) {
                            throw new IllegalArgumentException("fileName");
                        }
                        return fileName.trim();
                    }""".trimIndent(),
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
                    startLine = 4,
                    endLine = 6,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-upload-file"),
                ),
            ),
        )

        assertFalse(result.applied)
        assertTrue(result.warnings.any { it.contains("未授权", ignoreCase = true) || it.contains("allow", ignoreCase = true) })
    }

    fun testJavaReplaceMethodBodyOnlyChangesScopedMethodBody() {
        val before = """
            package com.example;

            public class CommonController {
                public String download(String resource) {
                    return resource;
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).applyToText(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-upload-file-body",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-upload-file",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """{
                        if (fileName == null || fileName.isBlank()) {
                            throw new IllegalArgumentException("fileName");
                        }
                        return fileName.trim();
                    }""".trimIndent(),
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
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-upload-file"),
                ),
            ),
        )

        assertTrue(result.applied, result.warnings.joinToString(" | "))
        assertTrue(result.updatedText.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(result.updatedText.contains("return fileName.trim();"))
        assertTrue(result.updatedText.contains("public String download(String resource) {\n        return resource;\n    }"))
    }

    fun testJavaAddImportFieldAndMethodAfterWithinAuthorizedType() {
        val before = """
            package com.example;

            public class CommonController {
                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).applyToText(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-add-import",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-upload-file",
                    kind = CodeEditOperationKind.ADD_IMPORT,
                    payload = "import java.util.Objects;",
                ),
                CodeEditOperation(
                    operationId = "op-add-field",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-upload-file",
                    kind = CodeEditOperationKind.ADD_FIELD,
                    payload = "private final String uploadPrefix = \"tmp\";",
                ),
                CodeEditOperation(
                    operationId = "op-insert-method",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-upload-file",
                    kind = CodeEditOperationKind.INSERT_METHOD_AFTER,
                    payload = """
                        public String uploadPreview(String fileName) {
                            return uploadPrefix + ":" + Objects.requireNonNull(fileName);
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
                    startLine = 4,
                    endLine = 6,
                    allowedChangeKinds = listOf("ADD_IMPORT", "ADD_FIELD", "INSERT_METHOD_AFTER"),
                    supportingFindingIds = listOf("finding-upload-file"),
                ),
            ),
        )

        assertTrue(result.applied, result.warnings.joinToString(" | "))
        assertTrue(result.updatedText.contains("import java.util.Objects;"))
        assertTrue(result.updatedText.contains("private final String uploadPrefix = \"tmp\";"))
        assertTrue(result.updatedText.contains("public String uploadPreview(String fileName)"))
        assertTrue(result.updatedText.contains("return fileName;"))
    }
}
