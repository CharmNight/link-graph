package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 为计划生成、代码草稿和问答确认流程提供稳定的诊断摘要，便于真实运行时快速定位输入输出是否偏离预期。
 */
internal object GenerationDiagnostics {
    fun summarizePlanningPayload(payload: PlanningPayload): String {
        val graph = payload.planningGraph
        val confirmedChanges = payload.snapshot.draftWorkbenchState.draftChanges
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

    fun summarizeCandidateChange(change: CandidateDraftChange): String {
        return buildString {
            append("changeId=").append(change.changeId)
            append(", title=").append(trimmed(change.title))
            append(", targets=").append(entryTitles(change.targetNodeIds))
            append(", before=").append(trimmed(change.beforeState))
            append(", after=").append(trimmed(change.afterState))
            append(", claimType=").append(change.claimType ?: "-")
            append(", evidenceLevels=").append(entryTitles(change.evidence.map { it.evidenceLevel.name }))
        }
    }

    fun summarizeDraftEntry(entry: DraftWorkbenchEntry): String {
        return buildString {
            append("entryId=").append(entry.entryId)
            append(", sourceChangeId=").append(entry.sourceChangeId ?: "null")
            append(", title=").append(trimmed(entry.title))
            append(", targets=").append(entryTitles(entry.targetNodeIds))
            append(", claimType=").append(entry.claimType ?: "-")
            append(", evidenceLevels=").append(entryTitles(entry.evidence.map { it.evidenceLevel.name }))
        }
    }

    fun summarizeGraphPatch(patch: GraphPatch?): String {
        if (patch == null) {
            return "null"
        }
        return buildString {
            append("summary=").append(trimmed(patch.summary))
            append(", operations=").append(patch.operations.size)
            append(", opTargets=").append(
                entryTitles(
                    patch.operations.map { operation ->
                        buildString {
                            append(operation.action.name)
                            append(':')
                            append(operation.elementId)
                            operation.node?.title?.takeIf(String::isNotBlank)?.let {
                                append(':').append(trimmed(it))
                            }
                        }
                    },
                ),
            )
            append(", addedNodes=").append(entryTitles(patch.addedNodeIds))
            append(", removedNodes=").append(entryTitles(patch.removedNodeIds))
            append(", addedEdges=").append(entryTitles(patch.addedEdgeIds))
            append(", removedEdges=").append(entryTitles(patch.removedEdgeIds))
        }
    }

    fun summarizeNodeStates(
        graph: GraphDocument,
        nodeIds: Collection<String>,
    ): String {
        if (nodeIds.isEmpty()) {
            return "[]"
        }
        val nodesById = graph.nodes.associateBy { it.id }
        val values = nodeIds
            .filter { it.isNotBlank() }
            .distinct()
            .map { nodeId ->
                val node = nodesById[nodeId]
                if (node == null) {
                    "$nodeId:<missing>"
                } else {
                    "$nodeId:${trimmed(node.title)}"
                }
            }
        return entryTitles(values)
    }

    private fun previewTitles(values: List<String>): String = entryTitles(values)

    private fun entryTitles(values: List<String>): String {
        if (values.isEmpty()) {
            return "[]"
        }
        return values.take(3).joinToString(
            prefix = "[",
            postfix = if (values.size > 3) ", ...]" else "]",
        ) { trimmed(it) }
    }

    private fun trimmed(value: String?): String {
        val normalized = value?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return "-"
        }
        return if (normalized.length <= 96) normalized else normalized.take(93) + "..."
    }
}
