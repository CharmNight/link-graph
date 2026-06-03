package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome

class SubjectGraphStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preservePreviousStatusKind: Boolean = false,
    ) {
        stateService.workbench.markOperationFeedback(
            level.toOperationFeedbackLevel(),
            message,
            preservePreviousStatusKind = preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        stateService.graph.switchAnalysisDisplayMode(displayMode)
        requestBrowserSync()
    }

    fun presentSelectedMethod(signature: String) {
        stateService.graph.pushSelectedMethod(signature)
        requestBrowserSync()
    }

    fun presentAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) {
        stateService.graph.loadAnalysisOutcome(outcome, source)
        requestBrowserSync()
    }

    fun presentDebugGraphLoaded(
        graph: com.charmnight.linkgraph.model.GraphDocument,
        source: String,
        selectedMethodSignature: String,
        summary: String,
    ) {
        stateService.graph.loadGraphProjection(
            visibleGraph = graph,
            fullGraph = graph,
            source = source,
            selectedMethodSignature = selectedMethodSignature,
        )
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.INFO,
            "已自动载入诊断链路图：$summary",
        )
        requestBrowserSync()
    }

    fun presentResourceNodeAdded(
        selectedNodeId: String,
        statusMessage: String,
    ) {
        stateService.workbench.markOperationFeedback(OperationFeedbackLevel.SUCCESS, statusMessage)
        stateService.graph.selectNode(selectedNodeId)
        requestBrowserSync()
    }
}
