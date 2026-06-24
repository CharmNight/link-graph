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

/**
 * 主题语义分析工作流。
 *
 * 负责以非阻塞读操作的方式对当前选定的主题（类、方法等代码元素）执行语义分析，
 * 生成可见图谱与完整图谱结果，并通过结果应用器将产出回写到工作台。
 */
internal class SubjectAnalysisWorkflow(
    private val dependencies: SubjectGraphWorkflowDependencies,
    private val state: SubjectGraphWorkflowState,
    private val requestCoordinator: SubjectGraphRequestCoordinator,
    private val resultApplier: SubjectAnalysisResultApplier,
    private val useCase: SubjectGraphUseCase,
) {
    /**
     * 提交一次针对指定主题的异步语义分析任务。
     *
     * 当主题为代码主题时进入智能模式等待索引就绪；任务完成后切回 UI 线程进行结果分发，
     * 并通过请求协调器管理最新的分析任务引用以便于取消。
     *
     * @param handle 当前要分析的主题句柄
     * @param requestId 本次请求的唯一标识，用于后续判断是否仍为最新请求
     * @param source 触发来源描述，用于结果应用时的可追溯性
     */
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

    /**
     * 在 UI 线程中处理异步分析结果。
     *
     * 当项目已销毁或请求已被更新取代时直接忽略；针对取消、失败、空结果等不同情形
     * 分别输出日志或反馈，并将成功的结果转交给结果应用器进行图谱更新。
     */
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

    /**
     * 在读操作上下文中执行实际的语义分析计算。
     *
     * 先确定有效的展示模式，调用语义分析器得到原始结果，再通过产出工厂构建图谱产出，
     * 并对各阶段（分析、产出构建、整体）记录耗时与关键统计信息以便追踪。
     */
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

    /**
     * 判断给定异常是否属于可被安全忽略的取消类异常。
     *
     * 处理 IntelliJ 的进程取消、容器已销毁以及通用取消异常，并递归检查异常原因链，
     * 用于在异步分析失败时区分真正的错误与正常的任务取消。
     */
    private fun isBenignCurrentSubjectGraphCancellation(throwable: Throwable): Boolean {
        if (throwable is ProcessCanceledException || throwable is AlreadyDisposedException || throwable is CancellationException) {
            return true
        }
        return throwable.cause?.let(::isBenignCurrentSubjectGraphCancellation) == true
    }
}
