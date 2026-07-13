package com.charmnight.linkgraph.codegen

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodeDraftCommandTest {
    @Test
    fun patchCommandRejectsCreateFileOperation() {
        val error = assertFailsWith<IllegalArgumentException> {
            CodeDraftCommand.PatchExistingFile(
                targetPath = "src/Sample.kt",
                operations = listOf(
                    CodeEditOperation(
                        operationId = "create",
                        filePath = "src/Sample.kt",
                        kind = CodeEditOperationKind.CREATE_FILE,
                        payload = "class Sample",
                    ),
                ),
                scopes = emptyList(),
            )
        }

        assertTrue(error.message.orEmpty().contains("CREATE_FILE"))
    }

    @Test
    fun patchCommandRejectsOperationForAnotherTargetPath() {
        val error = assertFailsWith<IllegalArgumentException> {
            CodeDraftCommand.PatchExistingFile(
                targetPath = "src/Sample.kt",
                operations = listOf(
                    CodeEditOperation(
                        operationId = "patch",
                        filePath = "src/Other.kt",
                        kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                        payload = "{ return }",
                    ),
                ),
                scopes = emptyList(),
            )
        }

        assertTrue(error.message.orEmpty().contains("targetPath"))
    }
}
