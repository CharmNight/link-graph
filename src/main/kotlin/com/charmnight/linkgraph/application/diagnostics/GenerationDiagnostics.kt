package com.charmnight.linkgraph.application.diagnostics

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.diagnostics.GraphPatchDiagnostics
import com.charmnight.linkgraph.workbench.CandidateDraftDiagnostics
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 为计划生成、代码草稿和问答确认流程提供稳定的诊断摘要，便于真实运行时快速定位输入输出是否偏离预期。
 */
internal object GenerationDiagnostics {
    fun summarizePlanningPayload(payload: PlanningInput): String {
        val graph = payload.planningGraph
        val confirmedChanges = payload.confirmedChanges
        return buildString {
            append("graphNodes=").append(graph.nodes.size)
            append(", graphEdges=").append(graph.edges.size)
            append(", diffEntries=").append(payload.diff.entries.size)
            append(", previewItems=").append(payload.previewItems.size)
            append(", confirmedChanges=").append(confirmedChanges.size)
            append(", sourceSnippets=").append(payload.sourceContext.size)
            append(", previewTitles=").append(previewTitles(payload.previewItems.map { it.title }))
            append(", confirmedChangeTitles=").append(entryTitles(confirmedChanges.map { it.title }))
        }
    }

    fun summarizePlan(plan: GenerationPlan): String {
        return buildString {
            append("source=").append(plan.source.name)
            append(", items=").append(plan.items.size)
            append(", warnings=").append(plan.warnings.size)
            append(", itemTargets=").append(entryTitles(plan.items.mapNotNull { it.targetPath ?: it.title }))
            append(", summary=").append(trimmed(plan.summary))
        }
    }

    fun summarizeCodeGenerationResult(result: CodeGenerationResult): String {
        return buildString {
            append("source=").append(result.source.name)
            append(", drafts=").append(result.drafts.size)
            append(", warnings=").append(result.warnings.size)
            append(", draftTargets=").append(entryTitles(result.drafts.map(GeneratedCodeDraft::targetPath)))
        }
    }

    fun summarizeWriteReport(report: GeneratedCodeDraftWriteReport): String {
        return buildString {
            append("written=").append(report.writtenFiles.size)
            append(", skipped=").append(report.skippedFiles.size)
            append(", warnings=").append(report.warnings.size)
            append(", writtenFiles=").append(entryTitles(report.writtenFiles))
            append(", skippedFiles=").append(entryTitles(report.skippedFiles))
        }
    }

    fun summarizeCandidateChange(change: CandidateDraftChange): String =
        CandidateDraftDiagnostics.summarizeCandidateChange(change)

    fun summarizeDraftEntry(entry: DraftWorkbenchEntry): String =
        CandidateDraftDiagnostics.summarizeDraftEntry(entry)

    fun summarizeGraphPatch(patch: GraphPatch?): String =
        GraphPatchDiagnostics.summarizeGraphPatch(patch)

    fun summarizeNodeStates(
        graph: GraphDocument,
        nodeIds: Collection<String>,
    ): String = GraphPatchDiagnostics.summarizeNodeStates(graph, nodeIds)

    private fun previewTitles(values: List<String>): String = entryTitles(values)

    private fun entryTitles(values: List<String>): String = GraphPatchDiagnostics.entryTitles(values)

    private fun trimmed(value: String?): String = GraphPatchDiagnostics.trimmed(value)
}
