package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.review.ReviewGraphResult

class ArchitectureGraphStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentIndexedGraphRequestStarted(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.beginIndexedGraphRequest(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentIndexedGraphRequestFailed(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.markIndexedGraphRequestFailed(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentArchitectureGraph(
        view: ArchitectureGraphResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadArchitectureGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentClassDiagram(
        view: ClassDiagramResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadClassDiagramView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentReviewGraph(
        view: ReviewGraphResult,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadReviewGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }
}
