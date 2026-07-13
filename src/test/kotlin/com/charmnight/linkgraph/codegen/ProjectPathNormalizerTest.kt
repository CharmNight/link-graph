package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.agent.model.EditScope
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GenerationPlanItem
import com.charmnight.linkgraph.agent.model.GenerationPlanSource
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProjectPathNormalizerTest {
    @Test
    fun normalizesProjectScopedPlanAndDraftPathsToProjectRelative() {
        val projectBasePath = "/workspace/demo"
        val absoluteProjectFile = "/workspace/demo/ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java"
        val externalFile = "/tmp/ExternalController.java"

        val plan = GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = "plan",
            items = listOf(
                GenerationPlanItem(
                    id = "plan-upload-file",
                    title = "修改 uploadFile",
                    description = "desc",
                    risk = SyncPreviewRisk.MEDIUM,
                    targetPath = absoluteProjectFile,
                ),
                GenerationPlanItem(
                    id = "plan-external",
                    title = "外部文件",
                    description = "desc",
                    risk = SyncPreviewRisk.HIGH,
                    targetPath = externalFile,
                ),
            ),
        )

        val result = CodeGenerationResult(
            drafts = listOf(
                GeneratedCodeDraft.patchExistingFile(
                    id = "draft-upload-file",
                    sourceNodeId = "method:upload-file",
                    title = "CommonController.java",
                    targetPath = absoluteProjectFile,
                    editOperations = listOf(
                        CodeEditOperation(
                            operationId = "op-upload-file",
                            filePath = absoluteProjectFile,
                            scopeId = "scope-upload-file",
                            kind = CodeEditOperationKind.REPLACE_METHOD_BLOCK,
                            payload = "public AjaxResult uploadFile() { return null; }",
                        ),
                    ),
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-upload-file",
                            targetNodeId = "method:upload-file",
                            filePath = absoluteProjectFile,
                            language = "JAVA",
                            symbolKind = "METHOD",
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                        ),
                    ),
                ),
            ),
            source = LlmResultSource.REMOTE,
        )

        val normalizedPlan = ProjectPathNormalizer.normalizePlan(plan, projectBasePath)
        val normalizedResult = ProjectPathNormalizer.normalizeDraftResult(result, projectBasePath)

        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedPlan.items.first().targetPath,
        )
        assertEquals(externalFile, normalizedPlan.items.last().targetPath)

        val normalizedDraft = normalizedResult.drafts.single()
        val normalizedPatch = assertIs<CodeDraftCommand.PatchExistingFile>(normalizedDraft.command)
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedDraft.targetPath,
        )
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedPatch.operations.single().filePath,
        )
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedPatch.scopes.single().filePath,
        )
    }
}
