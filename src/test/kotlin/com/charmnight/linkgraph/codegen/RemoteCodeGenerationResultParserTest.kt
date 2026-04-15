package com.charmnight.linkgraph.codegen

import kotlin.test.Test
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
}
