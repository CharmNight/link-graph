package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals

class ValidateEditScopeToolTest : BasePlatformTestCase() {
    fun testRejectsExistingFileDraftWithoutValidatedScope() {
        val result = ValidateEditScopeTool(ValidationToolFacade()).invoke(
            input = mapOf(
                "draft" to GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:upload-file",
                    title = "rewrite upload",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-1",
                            filePath = "src/main/java/com/example/CommonController.java",
                            scopeId = "scope-upload",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                            payload = "return;",
                        ),
                    ),
                ),
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals(false, result.payload["valid"])
    }

    fun testAcceptsExistingFileDraftWithValidatedScope() {
        val scope = EditScope(
            scopeId = "scope-upload",
            targetNodeId = "method:upload-file",
            filePath = "src/main/java/com/example/CommonController.java",
            language = "JAVA",
            symbolKind = "METHOD",
            symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
            startLine = 10,
            endLine = 20,
            allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
        )
        val result = ValidateEditScopeTool(ValidationToolFacade()).invoke(
            input = mapOf(
                "draft" to GeneratedCodeDraft(
                    id = "draft-1",
                    sourceNodeId = "method:upload-file",
                    title = "rewrite upload",
                    targetPath = "src/main/java/com/example/CommonController.java",
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-1",
                            filePath = "src/main/java/com/example/CommonController.java",
                            scopeId = "scope-upload",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                            payload = "return;",
                        ),
                    ),
                    editScopes = listOf(scope),
                ),
            ),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(),
                artifactStore = com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals(true, result.payload["valid"])
    }
}
