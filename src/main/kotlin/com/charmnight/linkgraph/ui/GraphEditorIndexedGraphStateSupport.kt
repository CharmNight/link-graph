package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult

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
        view: ArchitectureGraphResult,
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
        view: ClassDiagramResult,
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
        view: ReviewGraphResult,
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
