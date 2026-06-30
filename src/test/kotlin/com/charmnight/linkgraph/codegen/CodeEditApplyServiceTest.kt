package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.agent.model.EditScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeEditApplyServiceTest : BasePlatformTestCase() {
    fun testJavaReplaceMethodBlockAcceptsStatementOnlyPayload() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String filePath, Boolean delete) {
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-replace-delete-branch",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-file-download",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                    payload = """
                        if (Boolean.TRUE.equals(delete) && new File(filePath).exists())
                        {
                            FileUtils.deleteFile(filePath);
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-file-download",
                    targetNodeId = "method:file-download",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "METHOD",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 4,
                    endLine = 8,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-file-download"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("Boolean.TRUE.equals(delete)"))
        assertTrue(result.previewText.contains("new File(filePath).exists()"))
        assertFalse(result.previewText.contains("if (delete)"))
    }

    fun testPrepareJavaMethodBlockReplacementExposesExactPatchPreview() {
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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertEquals(1, result.preparedEdits.size)
        val prepared = result.preparedEdits.single()
        assertEquals("op-upload-file", prepared.operationId)
        assertEquals("scope-upload-file", prepared.scopeId)
        assertEquals("com.example.CommonController.uploadFile(java.lang.String):java.lang.String", prepared.targetSymbolSignature)
        assertTrue(prepared.startOffset < prepared.endOffset)
        assertTrue(prepared.beforeText.contains("return fileName;"))
        assertTrue(prepared.afterText.contains("return fileName.trim();"))
        assertTrue(result.previewText.contains("return fileName.trim();"))
        assertTrue(result.previewText.contains("return resource;"))
    }

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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(result.previewText.contains("return fileName.trim();"))
        assertTrue(result.previewText.contains("public String download(String resource) {\n        return resource;\n    }"))
        assertFalse(result.previewText.contains("return fileName;\n    }"))
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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("""require(fileName.isNotBlank()) { "fileName" }"""))
        assertTrue(result.previewText.contains("return fileName.trim()"))
        assertTrue(result.previewText.contains("fun download(resource: String): String {\n        return resource\n    }"))
        assertFalse(result.previewText.contains("return fileName\n    }"))
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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertFalse(result.canApply)
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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("""throw new IllegalArgumentException("fileName")"""))
        assertTrue(result.previewText.contains("return fileName.trim();"))
        assertTrue(result.previewText.contains("public String download(String resource) {\n        return resource;\n    }"))
    }

    fun testJavaFlowScopeReplacementOnlyChangesScopedBranch() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                    String filePath = "/tmp/" + realFileName;
                    response.setContentType("application/octet-stream");
                    FileUtils.writeBytes(filePath, response.getOutputStream());
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                    log.info("downloaded {}", realFileName);
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-branch",
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
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 9,
                    endLine = 11,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("String realFileName = System.currentTimeMillis()"))
        assertTrue(result.previewText.contains("FileUtils.writeBytes(filePath, response.getOutputStream());"))
        assertTrue(result.previewText.contains("log.info(\"downloaded {}\", realFileName);"))
        assertTrue(result.previewText.contains("if (Boolean.TRUE.equals(delete))"))
        assertFalse(result.previewText.contains("if (delete)"))
        assertEquals(1, result.preparedEdits.size)
        assertTrue(result.preparedEdits.single().beforeText.contains("delete"))
        assertTrue(result.preparedEdits.single().afterText.contains("Boolean.TRUE.equals(delete)"))
        assertFalse(result.preparedEdits.single().afterText.contains("String realFileName"))
    }

    fun testJavaFlowScopeReplacementAcceptsJsonWrappedReplacementPayload() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                    String filePath = "/tmp/" + realFileName;
                    FileUtils.writeBytes(filePath, response.getOutputStream());
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-branch",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = "{\"replacement\":\"if (Boolean.TRUE.equals(delete)) {\\n    FileUtils.deleteFile(filePath);\\n}\"}",
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("if (Boolean.TRUE.equals(delete))"))
        assertFalse(result.previewText.contains("\"replacement\""))
    }

    fun testJavaFlowScopeReplacementAcceptsMethodSignatureWrappedNewBodyPayload() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                    String filePath = "/tmp/" + realFileName;
                    FileUtils.writeBytes(filePath, response.getOutputStream());
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-branch",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = "{\"methodSignature\":\"com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void\",\"newBody\":\"if (Boolean.TRUE.equals(delete)) {\\n    FileUtils.deleteFile(filePath);\\n}\"}",
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("if (Boolean.TRUE.equals(delete))"))
        assertFalse(result.previewText.contains("methodSignature"))
        assertFalse(result.preparedEdits.single().afterText.contains("newBody"))
    }

    fun testJavaFlowScopeReplacementAcceptsReplaceWithPayload() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                    String filePath = "/tmp/" + realFileName;
                    FileUtils.writeBytes(filePath, response.getOutputStream());
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-branch",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = "{\"replace\":\"if (delete)\",\"with\":\"if (delete == true)\"}",
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("if (delete == true)"))
        assertFalse(result.previewText.contains("\"replace\""))
        assertFalse(result.previewText.contains("\"with\""))
    }

    fun testJavaFlowScopeConditionOnlyReplacementPreservesExistingBranchBody() {
        val before = """
            package com.example;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
                    String filePath = "/tmp/" + realFileName;
                    FileUtils.writeBytes(filePath, response.getOutputStream());
                    if (delete) {
                        FileUtils.deleteFile(filePath);
                    }
                }

                public String uploadFile(String fileName) {
                    return fileName;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-branch",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = "if (Boolean.TRUE.equals(delete))",
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 8,
                    endLine = 10,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("if (Boolean.TRUE.equals(delete))"), result.previewText)
        assertTrue(result.previewText.contains("FileUtils.deleteFile(filePath);"), result.previewText)
        assertTrue(result.previewText.contains("public String uploadFile(String fileName)"), result.previewText)
        assertFalse(result.warnings.any { it.contains("未授权的方法删除") })
    }

    fun testJavaScopedTryBlockReplacementPreservesTryWrapperAndSiblingMethods() {
        val before = """
            package com.example;

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

                public String[] uploadFiles(String[] files) {
                    return files;
                }

                public String resourceDownload(String resource) {
                    return resource;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-tighten-file-download-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
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
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 5,
                    endLine = 14,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                    supportingFindingIds = listOf("finding-file-download-try"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("try {"))
        assertTrue(result.previewText.contains("catch (Exception e)"))
        assertTrue(result.previewText.contains("Boolean.TRUE.equals(delete) && Files.exists(Path.of(filePath))"))
        assertTrue(result.previewText.contains("public String uploadFile(String fileName)"))
        assertTrue(result.previewText.contains("public String[] uploadFiles(String[] files)"))
        assertTrue(result.previewText.contains("public String resourceDownload(String resource)"))
    }

    fun testJavaMultipleFlowScopeReplacementsInSameMethodRemainStableWhenEarlierEditShiftsLaterLines() {
        val before = """
            package com.example;

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

                public String resourceDownload(String resource) {
                    return resource;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/example/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-tighten-allow-download-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-allow-download-guard",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """
                        if (!FileUtils.checkAllowDownload(fileName)) {
                            logger.warn("reject {}", fileName);
                            throw new Exception("bad file name");
                        }
                    """.trimIndent(),
                ),
                CodeEditOperation(
                    operationId = "op-tighten-delete-guard",
                    filePath = "src/main/java/com/example/CommonController.java",
                    scopeId = "scope-delete-guard",
                    kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                    payload = """
                        if (Boolean.TRUE.equals(delete) && Files.exists(Path.of(filePath))) {
                            FileUtils.deleteFile(filePath);
                        }
                    """.trimIndent(),
                ),
            ),
            editScopes = listOf(
                EditScope(
                    scopeId = "scope-allow-download-guard",
                    targetNodeId = "scope:file-download-allow",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 6,
                    endLine = 8,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-file-download-allow"),
                ),
                EditScope(
                    scopeId = "scope-delete-guard",
                    targetNodeId = "scope:file-download-delete",
                    filePath = "src/main/java/com/example/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.example.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 12,
                    endLine = 14,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-file-download-delete"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("""logger.warn("reject {}", fileName);"""))
        assertTrue(result.previewText.contains("Boolean.TRUE.equals(delete) && Files.exists(Path.of(filePath))"))
        assertTrue(result.previewText.contains("public String uploadFile(String fileName)"))
        assertTrue(result.previewText.contains("public String resourceDownload(String resource)"))
        assertEquals(2, result.preparedEdits.size)
    }

    fun testJavaFlowScopeReplacementClampsStaleRangeToOwnerMethodBoundary() {
        val before = """
            package com.ruoyi.web.controller.common;

            public class CommonController {
                public void fileDownload(String fileName, Boolean delete) {
                    try {
                        String filePath = "/tmp/" + fileName;
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

                public String[] uploadFiles(String[] files) {
                    return files;
                }

                public String resourceDownload(String resource) {
                    return resource;
                }
            }
        """.trimIndent()

        val result = CodeEditApplyService(project).prepareEdits(
            filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            beforeText = before,
            operations = listOf(
                CodeEditOperation(
                    operationId = "op-delete-guard",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    scopeId = "scope-delete-branch",
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
                    scopeId = "scope-delete-branch",
                    targetNodeId = "scope:file-download-if",
                    filePath = "src/main/java/com/ruoyi/web/controller/common/CommonController.java",
                    language = "JAVA",
                    symbolKind = "FLOW_SCOPE",
                    symbolSignature = "com.ruoyi.web.controller.common.CommonController.fileDownload(java.lang.String,java.lang.Boolean):void",
                    startLine = 7,
                    endLine = 23,
                    allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    supportingFindingIds = listOf("finding-delete-branch"),
                ),
            ),
        )

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertTrue(result.previewText.contains("if (Boolean.TRUE.equals(delete))"), result.previewText)
        assertTrue(result.previewText.contains("public String uploadFile(String fileName)"), result.previewText)
        assertTrue(result.previewText.contains("public String[] uploadFiles(String[] files)"), result.previewText)
        assertTrue(result.previewText.contains("public String resourceDownload(String resource)"), result.previewText)
        assertFalse(result.warnings.any { it.contains("未授权的方法删除") }, result.warnings.joinToString(" | "))
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

        val result = CodeEditApplyService(project).prepareEdits(
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

        assertTrue(result.canApply, result.warnings.joinToString(" | "))
        assertEquals(3, result.preparedEdits.size)
        assertTrue(result.previewText.contains("import java.util.Objects;"))
        assertTrue(result.previewText.contains("private final String uploadPrefix = \"tmp\";"))
        assertTrue(result.previewText.contains("public String uploadPreview(String fileName)"))
        assertTrue(result.previewText.contains("return fileName;"))
    }
}
