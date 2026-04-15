package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import kotlin.test.Test
import kotlin.test.assertEquals

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
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-upload-file",
                            targetNodeId = "method:upload-file",
                            filePath = absoluteProjectFile,
                            language = "JAVA",
                            symbolKind = "METHOD",
                            symbolSignature = "com.ruoyi.web.controller.common.CommonController.uploadFile(org.springframework.web.multipart.MultipartFile):com.ruoyi.common.core.domain.AjaxResult",
                            allowedChangeKinds = listOf("REPLACE_METHOD_BLOCK"),
                        ),
                    ),
                ),
                GenerationPlanItem(
                    id = "plan-external",
                    title = "外部文件",
                    description = "desc",
                    risk = SyncPreviewRisk.HIGH,
                    targetPath = externalFile,
                    editScopes = listOf(
                        EditScope(
                            scopeId = "scope-external",
                            targetNodeId = "method:external",
                            filePath = externalFile,
                            language = "JAVA",
                            symbolKind = "METHOD",
                        ),
                    ),
                ),
            ),
        )

        val result = CodeGenerationResult(
            drafts = listOf(
                GeneratedCodeDraft(
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
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedPlan.items.first().editScopes.single().filePath,
        )
        assertEquals(externalFile, normalizedPlan.items.last().targetPath)
        assertEquals(externalFile, normalizedPlan.items.last().editScopes.single().filePath)

        val normalizedDraft = normalizedResult.drafts.single()
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedDraft.targetPath,
        )
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedDraft.editOperations.single().filePath,
        )
        assertEquals(
            "ruoyi-admin/src/main/java/com/ruoyi/web/controller/common/CommonController.java",
            normalizedDraft.editScopes.single().filePath,
        )
    }
}
