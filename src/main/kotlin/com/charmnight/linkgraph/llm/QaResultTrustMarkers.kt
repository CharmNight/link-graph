package com.charmnight.linkgraph.llm

/**
 * QA 运行时证据的"已信任"内部标记字符串。
 *
 * 当 QA 运行时识别出某条证据已经被人工或更高级流程确认过，会在 warnings 列表中加入本标记。
 * 标记以 `INTERNAL:` 前缀开头，便于在最终对外展示前过滤掉这些内部信号。
 */
internal const val QA_RUNTIME_EVIDENCE_TRUSTED_MARKER: String = "INTERNAL:QA_RUNTIME_EVIDENCE_TRUSTED"

/**
 * 给一份图补丁结果打上"已信任运行时证据"的内部标记。
 * 用列表追加的方式实现，distinct() 用于防止重复打标。
 */
internal fun GraphPatchResult.markRuntimeEvidenceTrusted(): GraphPatchResult =
    copy(warnings = (warnings + QA_RUNTIME_EVIDENCE_TRUSTED_MARKER).distinct())

/**
 * 判断结果是否携带"已信任运行时证据"标记。
 * 用于上游决定是否要跳过对该结果的二次核验。
 */
internal fun GraphPatchResult.hasRuntimeEvidenceTrustedMarker(): Boolean =
    QA_RUNTIME_EVIDENCE_TRUSTED_MARKER in warnings

/**
 * 移除结果中的所有内部 QA 信任标记，返回一份"干净"的结果。
 * 在面向用户展示或对外输出前调用，避免内部信号泄漏到 UI。
 */
internal fun GraphPatchResult.withoutInternalQaTrustMarkers(): GraphPatchResult =
    copy(warnings = warnings.filterNot { warning -> warning == QA_RUNTIME_EVIDENCE_TRUSTED_MARKER })
