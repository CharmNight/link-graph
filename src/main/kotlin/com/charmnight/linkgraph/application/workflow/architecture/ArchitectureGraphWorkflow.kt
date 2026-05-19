package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.view.ArchitectureGraphProjector
import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

internal class ArchitectureGraphWorkflow(
    private val project: Project,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val logger: com.intellij.openapi.diagnostic.Logger,
) {
    fun requestArchitectureGraph() {
        eventSink.emit(
            GraphEditorApplicationEvent.Feedback(
                level = ApplicationFeedbackLevel.INFO,
                message = "正在构建项目架构索引。",
                preserveLastMessageType = true,
            ),
        )
        ReadAction
            .nonBlocking<ArchitectureGraphViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ArchitectureGraphViewResult.cancelled()
                }
                runCatching {
                    val index = indexSupport.buildIndex()
                    projector.project(index)
                }.fold(
                    onSuccess = { view -> ArchitectureGraphViewResult.success(view) },
                    onFailure = { error -> ArchitectureGraphViewResult.failure(error) },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("构建架构图失败", result.failure)
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.ERROR,
                                message = "加载架构图失败：${result.failure.message ?: result.failure.javaClass.simpleName}",
                                preserveLastMessageType = true,
                            ),
                        )
                    }
                    result.view != null -> {
                        eventSink.emit(GraphEditorApplicationEvent.ArchitectureGraphLoaded(result.view))
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.SUCCESS,
                                message = "已加载架构图。",
                            ),
                        )
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}

private data class ArchitectureGraphViewResult(
    val view: ArchitectureGraphViewDocument? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(view: ArchitectureGraphViewDocument): ArchitectureGraphViewResult = ArchitectureGraphViewResult(view = view)
        fun failure(error: Throwable): ArchitectureGraphViewResult = ArchitectureGraphViewResult(failure = error)
        fun cancelled(): ArchitectureGraphViewResult = ArchitectureGraphViewResult(cancelled = true)
    }
}
