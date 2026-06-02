package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome

internal class SubjectAnalysisResultApplier(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val state: SubjectGraphWorkflowState,
    private val useCase: SubjectGraphUseCase,
) {
    fun apply(
        analysisResult: SemanticAnalysisResult,
        source: String,
        reason: String,
        prebuiltOutcome: AnalysisOutcome? = null,
        requestedDisplayModeOverride: AnalysisDisplayMode? = null,
    ) {
        val effectiveDisplayMode = requestedDisplayModeOverride ?: useCase.effectiveAnalysisDisplayModeFor(
            subject = analysisResult.subject,
            requestedDisplayMode = state.requestedDisplayMode,
        )
        val outcomeStartedAt = System.nanoTime()
        val outcome = prebuiltOutcome?.takeIf { it.displayMode == effectiveDisplayMode }
            ?: dependencies.analysisOutcomeFactoryProvider().create(
                analysisResult = analysisResult,
                displayMode = effectiveDisplayMode,
                projectionPolicy = state.projectionSettings.toProjectionPolicy(),
            )
        traceStage(
            stage = if (prebuiltOutcome?.displayMode == effectiveDisplayMode) {
                "analysis.outcomeReuse"
            } else {
                "analysis.outcomeFactory.apply"
            },
            startedAtNanos = outcomeStartedAt,
        ) {
            outcomeSummaryDetails(outcome)
        }
        state.lastAnalyzedSubjectHandle = analysisResult.subject
        state.lastSemanticAnalysisResult = analysisResult
        state.requestedDisplayMode = outcome.displayMode
        dependencies.logGraphDiagnostics("semantic:$reason:visible", outcome.visibleGraph)
        if (outcome.fullGraph != outcome.visibleGraph) {
            dependencies.logGraphDiagnostics("semantic:$reason:full", outcome.fullGraph)
        }
        dependencies.invalidateQaRequests()
        val mutateStartedAt = System.nanoTime()
        dependencies.emit(GraphEditorApplicationEvent.AnalysisOutcomeLoaded(outcome, source))
        traceStage(
            stage = "analysis.applyState",
            startedAtNanos = mutateStartedAt,
        ) {
            outcomeSummaryDetails(outcome)
        }
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "统一语义分析加载完成[$reason]: source=$source, mode=${outcome.displayMode}, displayName=${outcome.displayName}, visibleNodes=${outcome.visibleGraph.nodes.size}, fullNodes=${outcome.fullGraph.nodes.size}"
        }
    }

    fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = dependencies.runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }

    fun outcomeSummaryDetails(outcome: AnalysisOutcome): List<String> {
        return listOf(
            "mode=${outcome.displayMode}",
            "visible=${LinkGraphRenderTrace.graphSummary(outcome.visibleGraph)}",
            "full=${LinkGraphRenderTrace.graphSummary(outcome.fullGraph)}",
            "factVisible=${LinkGraphRenderTrace.graphSummary(outcome.factGraphView?.visibleGraph)}",
            "flowVisible=${LinkGraphRenderTrace.graphSummary(outcome.flowchartView?.visibleGraph)}",
            "resourceVisible=${LinkGraphRenderTrace.graphSummary(outcome.resourceRelationView?.visibleGraph)}",
            "truncated=${outcome.projectionStats.truncated}",
        )
    }
}
