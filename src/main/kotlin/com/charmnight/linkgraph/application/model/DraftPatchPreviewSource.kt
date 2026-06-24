package com.charmnight.linkgraph.application.model

/**
 * 草稿补丁预览的来源类型。
 *
 * 同一份草稿补丁可能源自不同的入口（QA 结果、差异审查、上次应用过的快照），
 * 标记来源有助于后续在 UI 上展示溯源信息或限制某些操作（例如不允许从 LAST_APPLIED 再回到 QA）。
 */
enum class DraftPatchPreviewSource {
    /** 来自 QA 问答的产物。 */
    QA,

    /** 来自差异审查流程的产物。 */
    DIFF_REVIEW,

    /** 来自上次应用过、被保留为可恢复快照的产物。 */
    LAST_APPLIED,
}
