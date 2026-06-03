package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.review.ReviewGraphViewDocument

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
        view: ArchitectureGraphViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadArchitectureGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentClassDiagram(
        view: ClassDiagramViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadClassDiagramView(view, requestState, statusMessage)
        requestBrowserSync()
    }

    fun presentReviewGraph(
        view: ReviewGraphViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        stateService.indexedGraphs.loadReviewGraphView(view, requestState, statusMessage)
        requestBrowserSync()
    }
}
