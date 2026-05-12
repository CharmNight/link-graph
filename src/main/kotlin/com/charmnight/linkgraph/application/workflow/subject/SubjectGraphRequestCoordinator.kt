package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.workflow.CurrentSubjectGraphRequestTracker
import org.jetbrains.concurrency.CancellablePromise

internal class SubjectGraphRequestCoordinator {
    private val requestTracker = CurrentSubjectGraphRequestTracker()

    @Volatile
    private var currentAnalysisPromise: CancellablePromise<AnalysisOutcomeAsyncResult>? = null

    fun beginRequest(): Long {
        cancelCurrentAnalysis()
        return requestTracker.beginRequest()
    }

    fun isLatest(requestId: Long): Boolean = requestTracker.isLatest(requestId)

    fun replaceCurrentAnalysis(promise: CancellablePromise<AnalysisOutcomeAsyncResult>) {
        currentAnalysisPromise = promise
    }

    fun cancelCurrentAnalysis() {
        currentAnalysisPromise?.cancel()
        currentAnalysisPromise = null
    }
}
