package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.cacheState
import com.charmnight.linkgraph.application.indexed.reviewSelectedDiffItemIds
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureIndexWorkflowSupport
import com.charmnight.linkgraph.application.workflow.review.ReviewEvidenceWorkflowSupport
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.projection.business.ReviewGraphProjector
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicLong

internal class ReviewGraphWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val graphDiffer: GraphDiffer,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ReviewGraphProjector = ReviewGraphProjector(),
    private val reviewEvidenceSupport: ReviewEvidenceWorkflowSupport = ReviewEvidenceWorkflowSupport(project),
    private val logger: Logger,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private val requestIds = AtomicLong()

    fun requestIndexedGraph(request: IndexedGraphRequest) {
        val requestId = requestIds.incrementAndGet()
        val selectedDiffItemIds = request.reviewSelectedDiffItemIds()
        val runningState = AsyncRequestState.running(
            requestId = requestId,
            scene = request.view.name,
            statusMessage = "正在构建 Review Graph。",
        )
        eventSink.emit(
            GraphEditorApplicationEvent.IndexedGraphRequestStarted(
                view = IndexedGraphView.REVIEW,
                requestState = runningState,
                statusMessage = "正在构建 Review Graph。",
            ),
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = if (project.isDisposed) {
                ReviewGraphViewResult.cancelled()
            } else {
                runCatching {
                    val hadCachedFullIndex = indexSupport.hasFullIndex(request)
                    val cacheState = request.cacheState(hadCachedFullIndex)
                    val indexStartedAt = System.nanoTime()
                    val index = indexSupport.buildIndex(request)
                    traceStage("reviewGraph.buildIndex", indexStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val snapshotStartedAt = System.nanoTime()
                    val snapshot = snapshotProvider.snapshot()
                    val diff = snapshot.diff ?: snapshot.semanticFactGraph
                        .takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
                        ?.let { factGraph ->
                            snapshot.designBaselineGraph?.let { baseline -> graphDiffer.diff(factGraph, baseline).diff }
                        }
                    traceStage("reviewGraph.resolveDiff", snapshotStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "selectedDiffItemIds=${selectedDiffItemIds.size}",
                            "hasSnapshotDiff=${snapshot.diff != null}",
                            "diffEntries=${diff?.entries?.size ?: 0}",
                            "semanticFact=${LinkGraphRenderTrace.graphSummary(snapshot.semanticFactGraph)}",
                            "designBaseline=${LinkGraphRenderTrace.graphSummary(snapshot.designBaselineGraph)}",
                        )
                    }
                    val reviewService = reviewEvidenceSupport.reviewService(index)
                    val evidenceStartedAt = System.nanoTime()
                    val evidence = reviewEvidenceSupport.buildEvidence(diff, selectedDiffItemIds, reviewService)
                    traceStage("reviewGraph.buildEvidence", evidenceStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "selectedDiffItemIds=${selectedDiffItemIds.size}",
                            "changedSymbols=${evidence.bundle.changedSymbols.size}",
                            "upstream=${evidence.bundle.blastRadius.upstream.size}",
                            "downstream=${evidence.bundle.blastRadius.downstream.size}",
                            "relatedTests=${evidence.bundle.blastRadius.relatedTests.size}",
                            "evidenceRefs=${evidence.bundle.evidenceRefs.size}",
                            "gitChangedFiles=${evidence.bundle.gitChangedFiles.size}",
                            "warnings=${evidence.warnings.size}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(evidence.bundle, index, request, cacheState, indexSupport.freshness()).also { view ->
                        traceStage("reviewGraph.project", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                                "changedFiles=${view.changedFiles.size}",
                                "unmatchedHunks=${view.unmatchedHunks.size}",
                                "evidenceSnippets=${view.evidenceSnippets.size}",
                            )
                        }
                    }
                }.fold(
                    onSuccess = ReviewGraphViewResult::success,
                    onFailure = ReviewGraphViewResult::failure,
                )
            }
            ApplicationManager.getApplication().invokeLater({
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("构建 Review Graph 失败", result.failure)
                        val message = "加载 Review Graph 失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.REVIEW,
                                requestState = AsyncRequestState.failed(
                                    message = message,
                                    requestId = requestId,
                                    scene = request.view.name,
                                    startedAtEpochMillis = runningState.startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                    result.view != null -> {
                        eventSink.emit(
                            GraphEditorApplicationEvent.ReviewGraphLoaded(
                                view = result.view,
                                requestState = AsyncRequestState.succeeded(
                                    requestId = requestId,
                                    scene = request.view.name,
                                    statusMessage = "已加载 Review Graph。",
                                    startedAtEpochMillis = runningState.startedAtEpochMillis,
                                ),
                                statusMessage = "已加载 Review Graph。",
                            ),
                        )
                    }
                }
            }, ModalityState.defaultModalityState())
        }
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }
}

private data class ReviewGraphViewResult(
    val view: ReviewGraphResult? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(view: ReviewGraphResult): ReviewGraphViewResult = ReviewGraphViewResult(view = view)
        fun failure(error: Throwable): ReviewGraphViewResult = ReviewGraphViewResult(failure = error)
        fun cancelled(): ReviewGraphViewResult = ReviewGraphViewResult(cancelled = true)
    }
}
