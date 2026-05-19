package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.workflow.architecture.ArchitectureIndexWorkflowSupport
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.review.ReviewGraphProjector
import com.charmnight.linkgraph.review.ReviewGraphViewDocument
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal class ReviewGraphWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val graphDiffer: GraphDiffer,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ReviewGraphProjector = ReviewGraphProjector(),
    private val reviewEvidenceSupport: ReviewEvidenceWorkflowSupport = ReviewEvidenceWorkflowSupport(project),
    private val logger: Logger,
) {
    fun requestReviewGraph(selectedDiffItemIds: List<String> = emptyList()) {
        eventSink.emit(
            GraphEditorApplicationEvent.Feedback(
                level = ApplicationFeedbackLevel.INFO,
                message = "正在构建 Review Graph。",
                preserveLastMessageType = true,
            ),
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = if (project.isDisposed) {
                ReviewGraphViewResult.cancelled()
            } else {
                runCatching {
                    val index = indexSupport.buildIndex()
                    val snapshot = snapshotProvider.snapshot()
                    val diff = snapshot.diff ?: snapshot.semanticFactGraph
                        .takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
                        ?.let { factGraph ->
                            snapshot.designBaselineGraph?.let { baseline -> graphDiffer.diff(factGraph, baseline).diff }
                        }
                    val reviewService = reviewEvidenceSupport.reviewService(index)
                    val evidence = reviewEvidenceSupport.buildEvidence(diff, selectedDiffItemIds, reviewService)
                    projector.project(evidence.bundle)
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
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.ERROR,
                                message = "加载 Review Graph 失败：${result.failure.message ?: result.failure.javaClass.simpleName}",
                                preserveLastMessageType = true,
                            ),
                        )
                    }
                    result.view != null -> {
                        eventSink.emit(GraphEditorApplicationEvent.ReviewGraphLoaded(result.view))
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.SUCCESS,
                                message = "已加载 Review Graph。",
                            ),
                        )
                    }
                }
            }, ModalityState.defaultModalityState())
        }
    }

}

private data class ReviewGraphViewResult(
    val view: ReviewGraphViewDocument? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(view: ReviewGraphViewDocument): ReviewGraphViewResult = ReviewGraphViewResult(view = view)
        fun failure(error: Throwable): ReviewGraphViewResult = ReviewGraphViewResult(failure = error)
        fun cancelled(): ReviewGraphViewResult = ReviewGraphViewResult(cancelled = true)
    }
}
