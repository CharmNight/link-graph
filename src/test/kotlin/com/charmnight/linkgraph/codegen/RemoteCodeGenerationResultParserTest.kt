package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteCodeGenerationResultParserTest {
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
                      "editScopes": [],
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
            result.drafts.single().editOperations.single().payload,
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
                      "editScopes": [],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        )

        assertEquals("if (delete == true)", result.drafts.single().editOperations.single().payload)
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
                      "editScopes": [],
                      "warnings": []
                    }
                  ]
                }
            """.trimIndent(),
            promptPreview = "prompt",
        )

        val payload = result.drafts.single().editOperations.single().payload
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
                          "editScopes": [],
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
