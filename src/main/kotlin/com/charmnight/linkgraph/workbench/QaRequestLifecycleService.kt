package com.charmnight.linkgraph.workbench

import com.charmnight.linkgraph.ui.GraphEditorStateService
import java.util.UUID

class QaRequestLifecycleService {
    fun buildReplayableRequest(
        snapshot: GraphEditorStateService.Snapshot,
        question: String,
        selectedNodeIds: List<String>,
        sourceThreadId: String?,
    ): ReplayableQaRequest {
        return ReplayableQaRequest(
            requestId = UUID.randomUUID().toString(),
            kind = if (sourceThreadId.isNullOrBlank()) QaRequestKind.ASK else QaRequestKind.INVESTIGATE_THREAD,
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            baseSession = snapshot.auditResult?.auditSession,
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
