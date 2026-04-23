package com.charmnight.linkgraph.ui

internal fun AsyncRequestState.updatedPreviewOrNull(
    requestId: Long,
    previewText: String,
    finalizingStructuredResult: Boolean,
): AsyncRequestState? {
    if (this.requestId != requestId || phase != AsyncRequestPhase.RUNNING) {
        return null
    }
    return copy(
        streaming = true,
        streamPhase = if (finalizingStructuredResult) "FINALIZING" else "STREAMING",
        previewText = previewText,
        previewUpdatedAtEpochMillis = System.currentTimeMillis(),
        finalizingStructuredResult = finalizingStructuredResult,
    )
}
