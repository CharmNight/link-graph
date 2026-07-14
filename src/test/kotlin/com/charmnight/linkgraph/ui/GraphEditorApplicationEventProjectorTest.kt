package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.model.AsyncRequestPhase
import com.charmnight.linkgraph.application.model.AsyncRequestState
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphEditorApplicationEventProjectorTest {
    @Test
    fun invalidationResetsOnlyRunningAsyncRequestStates() {
        val stateService = GraphEditorStateService()
        stateService.asyncRequests.beginQaRequest(AsyncRequestState.running(requestId = 1L))
        stateService.asyncRequests.beginDiffReviewRequest(AsyncRequestState.running(requestId = 2L))
        stateService.asyncRequests.beginGraphBeautificationRequest(AsyncRequestState.running(requestId = 3L))
        stateService.asyncRequests.beginGenerationPlanRequest(AsyncRequestState.running(requestId = 4L))
        stateService.asyncRequests.beginGenerationPlanDiscussionRequest(AsyncRequestState.running(requestId = 5L))
        stateService.asyncRequests.beginCodeDraftRequest(AsyncRequestState.running(requestId = 6L))
        var syncRequests = 0
        val eventSink = GraphEditorApplicationEventProjector(
            stateService = stateService,
            requestBrowserSync = { syncRequests += 1 },
        ).eventSink()

        eventSink.emit(
            GraphEditorApplicationEvent.AsyncRequestsInvalidated(
                qaRequestId = 1L,
                diffReviewRequestId = 2L,
                beautificationRequestId = 3L,
                generationPlanRequestId = 4L,
                generationPlanDiscussionRequestId = 5L,
                codeDraftRequestId = 6L,
            ),
        )

        val snapshot = stateService.snapshot()
        assertEquals(AsyncRequestPhase.IDLE, snapshot.qaRequestState.phase)
        assertEquals(AsyncRequestPhase.IDLE, snapshot.diffReviewRequestState.phase)
        assertEquals(AsyncRequestPhase.IDLE, snapshot.graphBeautificationRequestState.phase)
        assertEquals(AsyncRequestPhase.IDLE, snapshot.generationPlanRequestState.phase)
        assertEquals(AsyncRequestPhase.IDLE, snapshot.generationPlanDiscussionRequestState.phase)
        assertEquals(AsyncRequestPhase.IDLE, snapshot.codeDraftRequestState.phase)
        assertEquals(1, syncRequests)
    }

    @Test
    fun invalidationPreservesCompletedAsyncRequestStates() {
        val stateService = GraphEditorStateService()
        stateService.asyncRequests.beginCodeDraftRequest(AsyncRequestState.succeeded(requestId = 7L))
        val eventSink = GraphEditorApplicationEventProjector(
            stateService = stateService,
            requestBrowserSync = {},
        ).eventSink()

        eventSink.emit(GraphEditorApplicationEvent.AsyncRequestsInvalidated(codeDraftRequestId = 7L))

        assertEquals(AsyncRequestPhase.SUCCEEDED, stateService.snapshot().codeDraftRequestState.phase)
    }

    @Test
    fun staleInvalidationDoesNotResetReplacementRequest() {
        val stateService = GraphEditorStateService()
        stateService.asyncRequests.beginQaRequest(AsyncRequestState.running(requestId = 1L))
        val staleInvalidation = GraphEditorApplicationEvent.AsyncRequestsInvalidated(qaRequestId = 1L)
        stateService.asyncRequests.beginQaRequest(AsyncRequestState.running(requestId = 2L))
        val eventSink = GraphEditorApplicationEventProjector(
            stateService = stateService,
            requestBrowserSync = {},
        ).eventSink()

        eventSink.emit(staleInvalidation)

        val qaState = stateService.snapshot().qaRequestState
        assertEquals(AsyncRequestPhase.RUNNING, qaState.phase)
        assertEquals(2L, qaState.requestId)
    }

    @Test
    fun emptyInvalidationDoesNotMutateUnidentifiedRunningState() {
        val stateService = GraphEditorStateService()
        stateService.asyncRequests.beginQaRequest(AsyncRequestState.running())
        val beforeInvalidation = stateService.snapshot()
        var syncRequests = 0
        val eventSink = GraphEditorApplicationEventProjector(
            stateService = stateService,
            requestBrowserSync = { syncRequests += 1 },
        ).eventSink()

        eventSink.emit(GraphEditorApplicationEvent.AsyncRequestsInvalidated())

        assertEquals(beforeInvalidation, stateService.snapshot())
        assertEquals(0, syncRequests)
    }
}
