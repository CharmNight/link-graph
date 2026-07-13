package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteCodeGenerationResultParserTest {
    @Test
    fun rejectsRemoteEditScopeFieldBecauseAuthorizationIsLocalOnly() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "class:created",
                          "title": "Created.java",
                          "targetPath": "src/main/java/com/example/Created.java",
                          "content": "package com.example; class Created {}",
                          "editOperations": [],
                          "editScopes": [],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message.orEmpty().contains("editScopes"))
        assertTrue(error.message.orEmpty().contains("locally"))
    }

    @Test
    fun rejectsDraftThatMixesFullContentWithEditOperations() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "class:created",
                          "title": "Created.java",
                          "targetPath": "src/main/java/com/example/Created.java",
                          "content": "package com.example; class Created {}",
                          "editOperations": [
                            {
                              "operationId": "op-1",
                              "filePath": "src/main/java/com/example/Created.java",
                              "scopeId": null,
                              "kind": "CREATE_FILE",
                              "payload": "package com.example; class Created {}",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message.orEmpty().contains("content"))
        assertTrue(error.message.orEmpty().contains("editOperations"))
    }

    @Test
    fun rejectsCreateFileMixedWithPatchOperations() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "class:created",
                          "title": "Created.java",
                          "targetPath": "src/main/java/com/example/Created.java",
                          "content": null,
                          "editOperations": [
                            {
                              "operationId": "op-create",
                              "filePath": "src/main/java/com/example/Created.java",
                              "scopeId": null,
                              "kind": "CREATE_FILE",
                              "payload": "package com.example; class Created {}",
                              "warnings": []
                            },
                            {
                              "operationId": "op-patch",
                              "filePath": "src/main/java/com/example/Created.java",
                              "scopeId": "scope-1",
                              "kind": "ADD_IMPORT",
                              "payload": "import java.util.List",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message.orEmpty().contains("CREATE_FILE"))
    }

    @Test
    fun rejectsEditOperationWhosePathDiffersFromDraftTarget() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "method:update",
                          "title": "Created.java",
                          "targetPath": "src/main/java/com/example/Created.java",
                          "content": null,
                          "editOperations": [
                            {
                              "operationId": "op-patch",
                              "filePath": "src/main/java/com/example/Other.java",
                              "scopeId": "scope-1",
                              "kind": "REPLACE_METHOD_BODY",
                              "payload": "{ return; }",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message.orEmpty().contains("filePath"))
        assertTrue(error.message.orEmpty().contains("targetPath"))
    }

    @Test
    fun rejectsCreateFileWithBlankPayload() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "class:created",
                          "title": "Created.java",
                          "targetPath": "src/main/java/com/example/Created.java",
                          "content": null,
                          "editOperations": [
                            {
                              "operationId": "op-create",
                              "filePath": "src/main/java/com/example/Created.java",
                              "scopeId": null,
                              "kind": "CREATE_FILE",
                              "payload": "   ",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message.orEmpty().contains("payload"))
    }

    @Test
    fun rejectsDraftWithoutContentOrEditOperations() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "method:file-download",
                          "title": "CommonController.java",
                          "targetPath": "src/main/java/com/example/CommonController.java"
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("content") == true || error.message?.contains("editOperations") == true)
    }

    @Test
    fun rejectsOversizedDraftContent() {
        val oversizedContent = "x".repeat(CodeDraftContentLimits.MAX_DRAFT_CONTENT_BYTES + 1)

        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "class:huge",
                          "title": "Huge.java",
                          "targetPath": "src/main/java/com/example/Huge.java",
                          "content": "$oversizedContent"
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("content") == true)
        assertTrue(error.message?.contains("too large", ignoreCase = true) == true)
    }

    @Test
    fun rejectsOversizedEditOperationPayload() {
        val oversizedPayload = "x".repeat(CodeDraftContentLimits.MAX_DRAFT_CONTENT_BYTES + 1)

        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "method:huge",
                          "title": "Huge.java",
                          "targetPath": "src/main/java/com/example/Huge.java",
                          "content": null,
                          "editOperations": [
                            {
                              "operationId": "op-1",
                              "filePath": "src/main/java/com/example/Huge.java",
                              "scopeId": "scope-1",
                              "kind": "REPLACE_METHOD_BODY",
                              "payload": "$oversizedPayload",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("payload") == true)
        assertTrue(error.message?.contains("too large", ignoreCase = true) == true)
    }

    @Test
    fun rejectsTooManyDrafts() {
        val drafts = (0..CodeDraftContentLimits.MAX_DRAFTS_PER_RESPONSE).joinToString(",") { index ->
            """
                {
                  "id": "draft-$index",
                  "sourceNodeId": "class:sample-$index",
                  "title": "Sample$index.java",
                  "targetPath": "src/main/java/com/example/Sample$index.java",
                  "content": "class Sample$index {}"
                }
            """.trimIndent()
        }

        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """{"warnings":[],"drafts":[$drafts]}""",
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("drafts") == true)
        assertTrue(error.message?.contains("exceed", ignoreCase = true) == true)
    }

    @Test
    fun rejectsTooManyEditOperationsInSingleDraft() {
        val operations = (0..CodeDraftContentLimits.MAX_EDIT_OPERATIONS_PER_DRAFT).joinToString(",") { index ->
            """
                {
                  "operationId": "op-$index",
                  "filePath": "src/main/java/com/example/Sample.java",
                  "scopeId": "scope-$index",
                  "kind": "REPLACE_METHOD_BODY",
                  "payload": "return $index;",
                  "warnings": []
                }
            """.trimIndent()
        }

        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "method:sample",
                          "title": "Sample.java",
                          "targetPath": "src/main/java/com/example/Sample.java",
                          "content": null,
                          "editOperations": [$operations],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("editOperations") == true)
        assertTrue(error.message?.contains("exceed", ignoreCase = true) == true)
    }

    @Test
    fun normalizesJsonWrappedReplacementPayloadIntoPlainEditText() {
        val result = RemoteCodeGenerationResultParser.parse(
            content = """
                {
                  "warnings": [],
                  "drafts": [
                    {
                      "id": "draft-1",
                      "sourceNodeId": "method:file-download",
                      "title": "CommonController.java",
                      "targetPath": "src/main/java/com/example/CommonController.java",
                      "content": null,
                      "editOperations": [
                        {
                          "operationId": "op-1",
                          "filePath": "src/main/java/com/example/CommonController.java",
                          "scopeId": "scope-1",
                          "kind": "REPLACE_METHOD_BODY",
                          "payload": "{\"replacement\":\"if (Boolean.TRUE.equals(delete)) {\\n    FileUtils.deleteFile(filePath);\\n}\"}",
                          "warnings": []
                        }
                      ],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        )

        assertEquals(
            """
                if (Boolean.TRUE.equals(delete)) {
                    FileUtils.deleteFile(filePath);
                }
            """.trimIndent(),
            assertIs<CodeDraftCommand.PatchExistingFile>(result.drafts.single().command).operations.single().payload,
        )
    }

    @Test
    fun normalizesReplaceWithPayloadIntoReplacementText() {
        val result = RemoteCodeGenerationResultParser.parse(
            content = """
                {
                  "warnings": [],
                  "drafts": [
                    {
                      "id": "draft-1",
                      "sourceNodeId": "scope:file-download-if",
                      "title": "CommonController.java",
                      "targetPath": "src/main/java/com/example/CommonController.java",
                      "content": null,
                      "editOperations": [
                        {
                          "operationId": "op-1",
                          "filePath": "src/main/java/com/example/CommonController.java",
                          "scopeId": "scope-1",
                          "kind": "REPLACE_METHOD_BODY",
                          "payload": "{\"replace\":\"if (delete)\",\"with\":\"if (delete == true)\"}",
                          "warnings": []
                        }
                      ],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        )

        val patch = assertIs<CodeDraftCommand.PatchExistingFile>(result.drafts.single().command)
        assertEquals("if (delete == true)", patch.operations.single().payload)
    }

    @Test
    fun normalizesMethodSignatureWrappedNewBodyPayloadIntoPlainEditText() {
        val result = RemoteCodeGenerationResultParser.parse(
            content = """
                {
                  "warnings": [],
                  "drafts": [
                    {
                      "id": "draft-1",
                      "sourceNodeId": "method:file-download",
                      "title": "CommonController.java",
                      "targetPath": "src/main/java/com/example/CommonController.java",
                      "content": null,
                      "editOperations": [
                        {
                          "operationId": "op-1",
                          "filePath": "src/main/java/com/example/CommonController.java",
                          "scopeId": "scope-1",
                          "kind": "REPLACE_METHOD_BODY",
                          "payload": "{\"methodSignature\":\"com.example.CommonController.fileDownload(java.lang.String):java.lang.String\",\"newBody\":\"{\\n    return baseUrl.trim();\\n}\"}",
                          "warnings": []
                        }
                      ],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        )

        val payload = assertIs<CodeDraftCommand.PatchExistingFile>(result.drafts.single().command)
            .operations.single().payload
        assertEquals(
            """
                {
                    return baseUrl.trim();
                }
            """.trimIndent(),
            payload,
        )
        assertFalse(payload.contains("methodSignature"))
        assertFalse(payload.contains("newBody"))
    }

    @Test
    fun rejectsMetadataOnlyJsonPayloadInsteadOfReturningItAsSource() {
        val error = assertFailsWith<IllegalStateException> {
            RemoteCodeGenerationResultParser.parse(
                content = """
                    {
                      "warnings": [],
                      "drafts": [
                        {
                          "id": "draft-1",
                          "sourceNodeId": "method:file-download",
                          "title": "CommonController.java",
                          "targetPath": "src/main/java/com/example/CommonController.java",
                          "content": null,
                          "editOperations": [
                            {
                              "operationId": "op-1",
                              "filePath": "src/main/java/com/example/CommonController.java",
                              "scopeId": "scope-1",
                              "kind": "REPLACE_METHOD_BODY",
                              "payload": "{\"methodSignature\":\"com.example.CommonController.fileDownload(java.lang.String):java.lang.String\",\"changeType\":\"REPLACE_METHOD_BODY\"}",
                              "warnings": []
                            }
                          ],
                          "warnings": []
                        }
                      ]
                    }
                """.trimIndent(),
                promptPreview = "prompt",
            )
        }

        assertTrue(error.message?.contains("payload") == true)
        assertTrue(error.message?.contains("metadata", ignoreCase = true) == true)
    }
}
