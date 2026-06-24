package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.model.diagnostics.GraphPatchDiagnostics

/**
 * 候选草稿与工作台草稿条目的诊断摘要工具。
 *
 * 把候选变更/草稿条目压缩为单行字符串，用于日志、错误信息等需要紧凑展示的场景。
 * 摘要刻意保留 changeId/entryId 等关键字段，方便从日志回溯到具体对象。
 */
internal object CandidateDraftDiagnostics {
    /**
     * 把候选变更对象摘要为单行字符串。
     * 包含变更 ID、标题、目标、前后状态、声明类型与证据级别。
     */
    fun summarizeCandidateChange(change: CandidateDraftChange): String {
        return buildString {
            append("changeId=").append(change.changeId)
            append(", title=").append(GraphPatchDiagnostics.trimmed(change.title))
            append(", targets=").append(GraphPatchDiagnostics.entryTitles(change.targetNodeIds))
            append(", before=").append(GraphPatchDiagnostics.trimmed(change.beforeState))
            append(", after=").append(GraphPatchDiagnostics.trimmed(change.afterState))
            // claimType 可能为 null，统一用 "-" 占位避免出现 "null"
            append(", claimType=").append(change.claimType ?: "-")
            append(", evidenceLevels=").append(GraphPatchDiagnostics.entryTitles(change.evidence.map { it.evidenceLevel.name }))
        }
    }

    /**
     * 把工作台草稿条目摘要为单行字符串。
     * 字段与 [summarizeCandidateChange] 类似，但反映的是已确认草稿的视角。
     */
    fun summarizeDraftEntry(entry: DraftWorkbenchEntry): String {
        return buildString {
            append("entryId=").append(entry.entryId)
            // sourceChangeId 可能不存在（手动创建的草稿），用 "null" 字符串显式区分
            append(", sourceChangeId=").append(entry.sourceChangeId ?: "null")
            append(", title=").append(GraphPatchDiagnostics.trimmed(entry.title))
            append(", targets=").append(GraphPatchDiagnostics.entryTitles(entry.targetNodeIds))
            append(", claimType=").append(entry.claimType ?: "-")
            append(", evidenceLevels=").append(GraphPatchDiagnostics.entryTitles(entry.evidence.map { it.evidenceLevel.name }))
        }
    }
}
