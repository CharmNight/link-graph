package com.charmnight.linkgraph.llm

internal const val QA_RUNTIME_EVIDENCE_TRUSTED_MARKER: String = "INTERNAL:QA_RUNTIME_EVIDENCE_TRUSTED"

internal fun GraphPatchResult.markRuntimeEvidenceTrusted(): GraphPatchResult =
    copy(warnings = (warnings + QA_RUNTIME_EVIDENCE_TRUSTED_MARKER).distinct())

internal fun GraphPatchResult.hasRuntimeEvidenceTrustedMarker(): Boolean =
    QA_RUNTIME_EVIDENCE_TRUSTED_MARKER in warnings

internal fun GraphPatchResult.withoutInternalQaTrustMarkers(): GraphPatchResult =
    copy(warnings = warnings.filterNot { warning -> warning == QA_RUNTIME_EVIDENCE_TRUSTED_MARKER })
