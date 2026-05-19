package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.view.ClassDiagramProjector
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

internal class ClassDiagramWorkflow(
    private val project: Project,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ClassDiagramProjector = ClassDiagramProjector(),
    private val logger: com.intellij.openapi.diagnostic.Logger,
) {
    fun requestClassDiagram(scopeNodeId: String? = null) {
        eventSink.emit(
            GraphEditorApplicationEvent.Feedback(
                level = ApplicationFeedbackLevel.INFO,
                message = if (scopeNodeId.isNullOrBlank()) "正在构建项目类图。" else "正在从架构节点下钻类图。",
                preserveLastMessageType = true,
            ),
        )
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                runCatching {
                    val index = indexSupport.buildIndex()
                    projector.project(index, scopeNodeId)
                }.fold(
                    onSuccess = { view -> ClassDiagramViewResult.success(view) },
                    onFailure = { error -> ClassDiagramViewResult.failure(error) },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("构建类图失败", result.failure)
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.ERROR,
                                message = "加载类图失败：${result.failure.message ?: result.failure.javaClass.simpleName}",
                                preserveLastMessageType = true,
                            ),
                        )
                    }
                    result.view != null -> {
                        eventSink.emit(GraphEditorApplicationEvent.ClassDiagramLoaded(result.view))
                        eventSink.emit(
                            GraphEditorApplicationEvent.Feedback(
                                level = ApplicationFeedbackLevel.SUCCESS,
                                message = "已加载类图。",
                            ),
                        )
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}

private data class ClassDiagramViewResult(
    val view: ClassDiagramViewDocument? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(view: ClassDiagramViewDocument): ClassDiagramViewResult = ClassDiagramViewResult(view = view)
        fun failure(error: Throwable): ClassDiagramViewResult = ClassDiagramViewResult(failure = error)
        fun cancelled(): ClassDiagramViewResult = ClassDiagramViewResult(cancelled = true)
    }
}
