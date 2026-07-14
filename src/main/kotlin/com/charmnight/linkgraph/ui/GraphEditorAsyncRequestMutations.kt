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

/** 失效时只清理 ID 匹配的运行中请求，保留新 generation 和已经完成的结果。 */
internal fun AsyncRequestState.idleIfRunning(invalidatedRequestId: Long?): AsyncRequestState =
    if (invalidatedRequestId != null && phase == AsyncRequestPhase.RUNNING && requestId == invalidatedRequestId) {
        AsyncRequestState()
    } else {
        this
    }
