package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphProjector
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.cacheState
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicLong

internal class ArchitectureGraphWorkflow(
    private val project: Project,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val logger: com.intellij.openapi.diagnostic.Logger,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private val requestIds = AtomicLong()

    fun requestIndexedGraph(request: IndexedGraphRequest) {
        val requestId = requestIds.incrementAndGet()
        val runningState = AsyncRequestState.running(
            requestId = requestId,
            scene = request.view.name,
            statusMessage = "正在构建项目结构索引。",
        )
        eventSink.emit(
            GraphEditorApplicationEvent.IndexedGraphRequestStarted(
                view = IndexedGraphView.ARCHITECTURE,
                requestState = runningState,
                statusMessage = "正在构建项目结构索引。",
            ),
        )
        AppExecutorUtil.getAppExecutorService().submit {
            val result =
                if (project.isDisposed) {
                    ArchitectureGraphViewResult.cancelled()
                } else {
                    runCatching {
                        val hadCachedFullIndex = indexSupport.hasFullIndex(request)
                        val indexStartedAt = System.nanoTime()
                        val index = indexSupport.buildIndex(request)
                        val cacheState = request.cacheState(hadCachedFullIndex)
                        traceStage("architectureGraph.buildIndex", indexStartedAt) {
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
                        val projectStartedAt = System.nanoTime()
                        projector.project(index, request, cacheState).also { view ->
                            traceStage("architectureGraph.project", projectStartedAt) {
                                listOf(
                                    "view=${request.view}",
                                    "cacheState=$cacheState",
                                    "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                    "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                    "truncated=${view.summary.truncated}",
                                    "hiddenNodes=${view.summary.hiddenNodeCount}",
                                    "hiddenEdges=${view.summary.hiddenEdgeCount}",
                                )
                            }
                        }
                    }.fold(
                        onSuccess = { view -> ArchitectureGraphViewResult.success(view) },
                        onFailure = { error -> ArchitectureGraphViewResult.failure(error) },
                    )
                }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) {
                    return@invokeLater
                }
                when {
                    result.cancelled -> Unit
                    result.failure != null -> {
                        logger.warn("构建项目结构失败", result.failure)
                        val message = "加载项目结构失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.ARCHITECTURE,
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
                            GraphEditorApplicationEvent.ArchitectureGraphLoaded(
                                view = result.view,
                                requestState = AsyncRequestState.succeeded(
                                    requestId = requestId,
                                    scene = request.view.name,
                                    statusMessage = "已加载项目结构。",
                                    startedAtEpochMillis = runningState.startedAtEpochMillis,
                                ),
                                statusMessage = "已加载项目结构。",
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

private data class ArchitectureGraphViewResult(
    val view: ArchitectureGraphResult? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(view: ArchitectureGraphResult): ArchitectureGraphViewResult = ArchitectureGraphViewResult(view = view)
        fun failure(error: Throwable): ArchitectureGraphViewResult = ArchitectureGraphViewResult(failure = error)
        fun cancelled(): ArchitectureGraphViewResult = ArchitectureGraphViewResult(cancelled = true)
    }
}
