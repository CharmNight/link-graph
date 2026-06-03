package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException

internal class SubjectAnalysisWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val state: SubjectGraphWorkflowState,
    private val requestCoordinator: SubjectGraphRequestCoordinator,
    private val resultApplier: SubjectAnalysisResultApplier,
    private val useCase: SubjectGraphUseCase,
) {
    fun submit(
        handle: SubjectHandle,
        requestId: Long,
        source: String,
    ) {
        var analysisTask = ReadAction
            .nonBlocking<AnalysisOutcomeAsyncResult> {
                if (dependencies.project.isDisposed) {
                    return@nonBlocking AnalysisOutcomeAsyncResult.cancelled()
                }
                try {
                    AnalysisOutcomeAsyncResult.success(computeAnalysisResultInReadAction(handle))
                } catch (throwable: Throwable) {
                    if (isBenignCurrentSubjectGraphCancellation(throwable)) {
                        AnalysisOutcomeAsyncResult.cancelled()
                    } else {
                        AnalysisOutcomeAsyncResult.failure(throwable)
                    }
                }
            }
            .expireWith(dependencies.project)
        if (handle is CodeSubjectHandle) {
            analysisTask = analysisTask.inSmartMode(dependencies.project)
        }
        val promise = analysisTask
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                handleResult(requestId, handle, source, result)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
        requestCoordinator.replaceCurrentAnalysis(promise)
    }

    private fun handleResult(
        requestId: Long,
        handle: SubjectHandle,
        source: String,
        result: AnalysisOutcomeAsyncResult,
    ) {
        if (dependencies.project.isDisposed || !requestCoordinator.isLatest(requestId)) {
            return
        }
        when {
            result.cancelled -> {
                debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                    "当前主体语义分析已取消: subject=${handle.displayName}"
                }
            }

            result.failure != null -> {
                dependencies.logger.warn("异步语义分析失败", result.failure)
                dependencies.emitFeedback(
                    ApplicationFeedbackLevel.ERROR,
                    "加载当前主体链路失败：${result.failure.message ?: result.failure.javaClass.simpleName}",
                )
            }

            result.result == null -> {
                dependencies.emitFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前主体在分析过程中失效，请重新触发链路分析。",
                )
            }

            else -> {
                resultApplier.apply(
                    analysisResult = result.result.analysisResult,
                    source = source,
                    reason = "async",
                    prebuiltOutcome = result.result.outcome,
                )
            }
        }
    }

    private fun computeAnalysisResultInReadAction(handle: SubjectHandle): AnalysisExecutionResult? {
        val totalStartedAt = System.nanoTime()
        val effectiveDisplayMode = useCase.effectiveAnalysisDisplayModeFor(
            subject = handle,
            requestedDisplayMode = state.requestedDisplayMode,
        )
        val analysisStartedAt = System.nanoTime()
        val analysisResult = dependencies.semanticAnalyzerProvider().analyze(
            handle = handle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = state.projectionSettings.toTraversalBudgetPolicy(),
        )
        resultApplier.traceStage(
            stage = "analysis.semanticAnalyzer",
            startedAtNanos = analysisStartedAt,
        ) {
            listOf(
                "subject=${handle.displayName}",
                "units=${analysisResult.semanticUnits.size}",
                "relations=${analysisResult.relations.size}",
                "anchors=${analysisResult.anchors.size}",
                "diagnostics=${analysisResult.diagnostics.size}",
            )
        }
        val outcomeStartedAt = System.nanoTime()
        val outcome = dependencies.analysisOutcomeFactoryProvider().create(
            analysisResult = analysisResult,
            displayMode = effectiveDisplayMode,
            projectionPolicy = state.projectionSettings.toProjectionPolicy(),
        )
        resultApplier.traceStage(
            stage = "analysis.outcomeFactory",
            startedAtNanos = outcomeStartedAt,
        ) {
            resultApplier.outcomeSummaryDetails(outcome)
        }
        resultApplier.traceStage(
            stage = "analysis.total",
            startedAtNanos = totalStartedAt,
        ) {
            listOf(
                "subject=${handle.displayName}",
                "mode=${outcome.displayMode}",
                "visible=${LinkGraphRenderTrace.graphSummary(outcome.visibleGraph)}",
                "full=${LinkGraphRenderTrace.graphSummary(outcome.fullGraph)}",
            )
        }
        return AnalysisExecutionResult(
            analysisResult = analysisResult,
            outcome = outcome,
        )
    }

    private fun isBenignCurrentSubjectGraphCancellation(throwable: Throwable): Boolean {
        if (throwable is ProcessCanceledException || throwable is AlreadyDisposedException || throwable is CancellationException) {
            return true
        }
        return throwable.cause?.let(::isBenignCurrentSubjectGraphCancellation) == true
    }
}
