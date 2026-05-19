package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.review.ReviewGraphViewDocument

class ArchitectureGraphStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentArchitectureGraph(view: ArchitectureGraphViewDocument) {
        stateService.graph.loadArchitectureGraphView(view)
        requestBrowserSync()
    }

    fun presentClassDiagram(view: ClassDiagramViewDocument) {
        stateService.graph.loadClassDiagramView(view)
        requestBrowserSync()
    }

    fun presentReviewGraph(view: ReviewGraphViewDocument) {
        stateService.graph.loadReviewGraphView(view)
        requestBrowserSync()
    }
}
