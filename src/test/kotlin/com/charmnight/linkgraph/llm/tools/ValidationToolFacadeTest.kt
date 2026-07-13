package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.codegen.CodeEditOperation
import com.charmnight.linkgraph.codegen.CodeEditOperationKind
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.artifact.CodeEvidenceArtifact
import kotlin.test.Test
import kotlin.test.assertTrue

class ValidationToolFacadeTest {
    @Test
    fun acceptsReadEvidenceWhenDraftIsProjectRelativeAndEvidenceIsAbsolute() {
        val projectBasePath = "/workspace/demo"
        val absolutePath = "/workspace/demo/src/main/java/com/example/CommonController.java"
        val facade = ValidationToolFacade()

        val accepted = facade.hasReadEvidence(
            draft = GeneratedCodeDraft.patchExistingFile(
                id = "draft-upload",
                sourceNodeId = "method:upload-file",
                title = "CommonController.java",
                targetPath = "src/main/java/com/example/CommonController.java",
                editOperations = listOf(
                    CodeEditOperation(
                        operationId = "op-upload",
                        filePath = "src/main/java/com/example/CommonController.java",
                        scopeId = "scope-upload",
                        kind = CodeEditOperationKind.REPLACE_METHOD_BODY,
                        payload = "return;",
                    ),
                ),
                editScopes = listOf(
                    EditScope(
                        scopeId = "scope-upload",
                        targetNodeId = "method:upload-file",
                        filePath = absolutePath,
                        language = "JAVA",
                        symbolKind = "METHOD",
                        symbolSignature = "com.example.CommonController.uploadFile(java.lang.String):void",
                        startLine = 10,
                        endLine = 20,
                        allowedChangeKinds = listOf("REPLACE_METHOD_BODY"),
                    ),
                ),
            ),
            evidenceArtifacts = listOf(
                CodeEvidenceArtifact(
                    artifactId = "evidence-upload",
                    nodeId = "method:upload-file",
                    filePath = absolutePath,
                    snippet = "void uploadFile(String file) {}",
                    startLine = 10,
                    endLine = 20,
                ),
            ),
            projectBasePath = projectBasePath,
        )

        assertTrue(accepted)
    }
}
