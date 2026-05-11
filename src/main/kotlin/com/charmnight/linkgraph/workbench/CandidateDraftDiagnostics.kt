package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.diagnostics.GraphPatchDiagnostics

internal object CandidateDraftDiagnostics {
    fun summarizeCandidateChange(change: CandidateDraftChange): String {
        return buildString {
            append("changeId=").append(change.changeId)
            append(", title=").append(GraphPatchDiagnostics.trimmed(change.title))
            append(", targets=").append(GraphPatchDiagnostics.entryTitles(change.targetNodeIds))
            append(", before=").append(GraphPatchDiagnostics.trimmed(change.beforeState))
            append(", after=").append(GraphPatchDiagnostics.trimmed(change.afterState))
            append(", claimType=").append(change.claimType ?: "-")
            append(", evidenceLevels=").append(GraphPatchDiagnostics.entryTitles(change.evidence.map { it.evidenceLevel.name }))
        }
    }

    fun summarizeDraftEntry(entry: DraftWorkbenchEntry): String {
        return buildString {
            append("entryId=").append(entry.entryId)
            append(", sourceChangeId=").append(entry.sourceChangeId ?: "null")
            append(", title=").append(GraphPatchDiagnostics.trimmed(entry.title))
            append(", targets=").append(GraphPatchDiagnostics.entryTitles(entry.targetNodeIds))
            append(", claimType=").append(entry.claimType ?: "-")
            append(", evidenceLevels=").append(GraphPatchDiagnostics.entryTitles(entry.evidence.map { it.evidenceLevel.name }))
        }
    }
}
