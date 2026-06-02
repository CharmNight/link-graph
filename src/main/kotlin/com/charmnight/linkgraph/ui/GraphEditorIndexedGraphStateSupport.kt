package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.review.ReviewGraphViewDocument

internal class GraphEditorIndexedGraphStateSupport(
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
) {
    fun beginIndexedGraphRequest(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        mutate { currentState ->
            currentState.withIndexedGraphRequestStarted(
                view = view,
                requestState = requestState,
                statusMessage = statusMessage,
            )
        }
    }

    fun markIndexedGraphRequestFailed(
        view: IndexedGraphView,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        mutate { currentState ->
            currentState.withIndexedGraphRequestFailed(
                view = view,
                requestState = requestState,
                statusMessage = statusMessage,
            )
        }
    }

    fun loadArchitectureGraphView(
        view: ArchitectureGraphViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        mutate { currentState ->
            currentState.withLoadedArchitectureGraphView(
                view = view,
                requestState = requestState,
                statusMessage = statusMessage,
            )
        }
    }

    fun loadClassDiagramView(
        view: ClassDiagramViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        mutate { currentState ->
            currentState.withLoadedClassDiagramView(
                view = view,
                requestState = requestState,
                statusMessage = statusMessage,
            )
        }
    }

    fun loadReviewGraphView(
        view: ReviewGraphViewDocument,
        requestState: AsyncRequestState,
        statusMessage: String,
    ) {
        mutate { currentState ->
            currentState.withLoadedReviewGraphView(
                view = view,
                requestState = requestState,
                statusMessage = statusMessage,
            )
        }
    }
}
