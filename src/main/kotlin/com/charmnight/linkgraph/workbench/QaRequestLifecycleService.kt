package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.llm.GraphPatchResult
import java.util.UUID

class QaRequestLifecycleService {
    fun buildReplayableRequest(
        qaResult: GraphPatchResult?,
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
        mode: QaMode = QaMode.AUTO,
    ): ReplayableQaRequest {
        return ReplayableQaRequest(
            requestId = UUID.randomUUID().toString(),
            kind = if (sourceThreadId.isNullOrBlank()) QaRequestKind.ASK else QaRequestKind.INVESTIGATE_THREAD,
            question = question,
            mode = mode,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            baseSession = qaResult?.qaSession,
        )
    }

    fun markSubmitted(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = null,
        )
    }

    fun markFailed(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = request,
        )
    }

    fun markSucceeded(
        currentState: QaRequestRecoveryState,
        request: ReplayableQaRequest,
    ): QaRequestRecoveryState {
        return currentState.copy(
            lastSubmittedRequest = request,
            lastFailedRequest = null,
        )
    }
}
