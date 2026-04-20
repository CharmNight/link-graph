package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanItem
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import kotlin.test.Test
import kotlin.test.assertTrue

class GenerationDiagnosticsTest {
    @Test
    fun summarizesPlanningPayloadWithConfirmedChangesAndPreviewItems() {
        val payload = PlanningPayload(
            planningGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:file-download",
                        type = NodeType.METHOD,
                        title = "CommonController.fileDownload",
                    ),
                ),
            ),
            diff = GraphDiff(
                entries = listOf(
                    GraphDiffEntry(
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = "method:file-download",
                        status = DiffStatus.MODIFIED,
                    ),
                ),
            ),
            previewItems = listOf(
                SyncPreviewItem(
                    id = "preview-file-download",
                    title = "调整 fileDownload 路径判定",
                    description = "在 CommonController.fileDownload 中补分支。",
                    risk = SyncPreviewRisk.MEDIUM,
                ),
            ),
            snapshot = GraphEditorStateService.Snapshot(
                draftWorkbenchState = com.charmnight.linkgraph.workbench.DraftWorkbenchState(
                    draftChanges = listOf(
                        DraftWorkbenchEntry(
                            entryId = "draft-change-file-download",
                            kind = DraftEntryKind.CHANGE,
                            sourceChangeId = "change-file-download",
                            title = "修改 fileDownload 的路径判定",
                            targetNodeIds = listOf("method:file-download"),
                            beforeState = "直接使用 baseUrl 拼接下载路径。",
                            afterState = "当 /usr 开头时改写到 /tmp；当 C:/ 开头时直接报错。",
                            reason = "统一处理 Linux 临时目录并阻止 Windows 路径。",
                            impactSummary = "影响下载路径解析。",
                        ),
                    ),
                ),
            ),
        )

        val summary = GenerationDiagnostics.summarizePlanningPayload(payload)

        assertTrue(summary.contains("graphNodes=1"))
        assertTrue(summary.contains("diffEntries=1"))
        assertTrue(summary.contains("previewItems=1"))
        assertTrue(summary.contains("confirmedChanges=1"))
        assertTrue(summary.contains("sourceSnippets=0"))
        assertTrue(summary.contains("修改 fileDownload 的路径判定"))
    }

    @Test
    fun summarizesPlanDraftsAndWriteReportForLogInspection() {
        val planSummary = GenerationDiagnostics.summarizePlan(
            GenerationPlan(
                source = GenerationPlanSource.REMOTE,
                summary = "修改 CommonController.fileDownload 的路径判定。",
                items = listOf(
                    GenerationPlanItem(
                        id = "plan-file-download",
                        title = "在 fileDownload 中新增 /usr 与 C:/ 前缀分支",
                        description = "先判断前缀，再决定 /tmp 映射或报错。",
                        risk = SyncPreviewRisk.MEDIUM,
                        targetPath = "src/main/java/com/example/CommonController.java",
                    ),
                ),
                warnings = listOf("需复核 FileUtils.checkAllowDownload 的顺序。"),
            ),
        )
        val resultSummary = GenerationDiagnostics.summarizeCodeGenerationResult(
            CodeGenerationResult(
                drafts = listOf(
                    GeneratedCodeDraft(
                        id = "draft-file-download",
                        sourceNodeId = "method:file-download",
                        title = "CommonController.java",
                        targetPath = "src/main/java/com/example/CommonController.java",
                        content = "class CommonController {}",
                    ),
                ),
                warnings = listOf("请复核异常类型。"),
                source = LlmResultSource.REMOTE,
                promptPreview = "prompt",
            ),
        )
        val reportSummary = GenerationDiagnostics.summarizeWriteReport(
            GeneratedCodeDraftWriteReport(
                writtenFiles = listOf("src/main/java/com/example/CommonController.java"),
                skippedFiles = listOf("src/main/java/com/example/FallbackController.java"),
                warnings = listOf("已跳过已有冲突文件。"),
            ),
        )

        assertTrue(planSummary.contains("source=REMOTE"))
        assertTrue(planSummary.contains("items=1"))
        assertTrue(planSummary.contains("CommonController.java"))
        assertTrue(resultSummary.contains("source=REMOTE"))
        assertTrue(resultSummary.contains("drafts=1"))
        assertTrue(resultSummary.contains("CommonController.java"))
        assertTrue(reportSummary.contains("written=1"))
        assertTrue(reportSummary.contains("skipped=1"))
        assertTrue(reportSummary.contains("FallbackController.java"))
    }
}
