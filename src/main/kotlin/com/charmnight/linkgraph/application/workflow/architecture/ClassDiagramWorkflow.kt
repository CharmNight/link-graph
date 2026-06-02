package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ClassDiagramProjector
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.indexed.cacheState
import com.charmnight.linkgraph.application.indexed.classDiagramScopeNodeId
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicLong

internal class ClassDiagramWorkflow(
    private val project: Project,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    snapshotProvider: EditorSnapshotProvider? = null,
    private val projector: ClassDiagramProjector = ClassDiagramProjector(),
    private val scopeResolver: ClassDiagramScopeResolver = ClassDiagramScopeResolver(project, snapshotProvider),
    private val logger: com.intellij.openapi.diagnostic.Logger,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    private val requestIds = AtomicLong()

    fun requestIndexedGraph(request: IndexedGraphRequest) {
        val requestId = requestIds.incrementAndGet()
        val scopeNodeId = request.classDiagramScopeNodeId()
        val startMessage = if (scopeNodeId.isNullOrBlank()) {
            "正在构建项目类图。"
        } else {
            "正在从架构节点下钻类图。"
        }
        val runningState = AsyncRequestState.running(
            requestId = requestId,
            scene = request.view.name,
            statusMessage = startMessage,
        )
        eventSink.emit(
            GraphEditorApplicationEvent.IndexedGraphRequestStarted(
                view = IndexedGraphView.CLASS_DIAGRAM,
                requestState = runningState,
                statusMessage = startMessage,
            ),
        )
        val resolvedScopeNodeId = runCatching {
            if (project.isDisposed) {
                null
            } else {
                scopeResolver.resolve(scopeNodeId)
            }
        }.onFailure { error ->
            logger.warn("解析类图范围失败，将回退到项目默认锚点", error)
        }.getOrNull()
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                var indexForCompleteBuild: com.charmnight.linkgraph.architecture.ArchitectureGraphIndex? = null
                runCatching {
                    val hasFullIndex = indexSupport.hasFullIndex(request)
                    val cacheState = request.cacheState(hasFullIndex)
                    val indexStartedAt = System.nanoTime()
                    val index = if (hasFullIndex) {
                        indexSupport.buildIndex(request)
                    } else {
                        indexSupport.buildClassDiagramStructureIndex(request)
                    }
                    indexForCompleteBuild = index
                    val symbolIndexHint = if (hasFullIndex) {
                        null
                    } else {
                        index.symbolIndex
                    }
                    val relationCompleteness = if (hasFullIndex) {
                        "COMPLETE"
                    } else {
                        ClassDiagramFastIndex.RELATION_COMPLETENESS_PARTIAL
                    }
                    traceStage(
                        if (hasFullIndex) {
                            "classDiagram.buildIndex"
                        } else {
                            "classDiagram.buildStructureIndex"
                        },
                        indexStartedAt,
                    ) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "requestedScopeNodeId=${scopeNodeId.orEmpty()}",
                            "resolvedScopeNodeId=${resolvedScopeNodeId.orEmpty()}",
                            "relationCompleteness=$relationCompleteness",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "fields=${index.symbolIndex.fieldsByQualifiedName.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(index, resolvedScopeNodeId, relationCompleteness, request, cacheState).also { view ->
                        traceStage("classDiagram.project", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "requestedScopeNodeId=${scopeNodeId.orEmpty()}",
                                "resolvedScopeNodeId=${resolvedScopeNodeId.orEmpty()}",
                                "relationCompleteness=$relationCompleteness",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                            )
                        }
                    }.let { view ->
                        ClassDiagramViewPayload(
                            view = view,
                            symbolIndexHint = symbolIndexHint,
                            resolvedScopeNodeId = resolvedScopeNodeId,
                            request = request,
                        )
                    }
                }.fold(
                    onSuccess = { payload -> ClassDiagramViewResult.success(payload) },
                    onFailure = { error ->
                        val partialIndex = indexForCompleteBuild
                        if (partialIndex != null && !indexSupport.hasFullIndex(request)) {
                            requestCompleteClassDiagram(
                                scopeNodeId = resolvedScopeNodeId,
                                symbolIndexHint = partialIndex.symbolIndex,
                                request = request,
                                requestId = requestId,
                                startedAtEpochMillis = runningState.startedAtEpochMillis,
                            )
                        }
                        ClassDiagramViewResult.failure(error)
                    },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("构建类图失败", result.failure)
                        val message = "加载类图失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.CLASS_DIAGRAM,
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
                    result.payload?.view != null -> {
                        val view = result.payload.view
                        val complete = view.summary.relationCompleteness == "COMPLETE"
                        if (complete) {
                            val message = "已加载类图。"
                            eventSink.emit(
                                GraphEditorApplicationEvent.ClassDiagramLoaded(
                                    view = view,
                                    requestState = AsyncRequestState.succeeded(
                                        requestId = requestId,
                                        scene = request.view.name,
                                        statusMessage = message,
                                        startedAtEpochMillis = runningState.startedAtEpochMillis,
                                    ),
                                    statusMessage = message,
                                ),
                            )
                        } else {
                            requestCompleteClassDiagram(
                                result.payload.resolvedScopeNodeId,
                                result.payload.symbolIndexHint,
                                result.payload.request,
                                requestId,
                                runningState.startedAtEpochMillis,
                            )
                        }
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun requestCompleteClassDiagram(
        scopeNodeId: String?,
        symbolIndexHint: JvmSymbolIndex?,
        request: IndexedGraphRequest,
        requestId: Long,
        startedAtEpochMillis: Long?,
    ) {
        ReadAction
            .nonBlocking<ClassDiagramViewResult> {
                if (project.isDisposed) {
                    return@nonBlocking ClassDiagramViewResult.cancelled()
                }
                runCatching {
                    val hadCachedFullIndex = indexSupport.hasFullIndex(request)
                    val cacheState = request.cacheState(hadCachedFullIndex)
                    val indexStartedAt = System.nanoTime()
                    val index = indexSupport.buildIndex(
                        request = request,
                        symbolIndexHint = symbolIndexHint,
                    )
                    traceStage("classDiagram.completeBuildIndex", indexStartedAt) {
                        listOf(
                            "view=${request.view}",
                            "cacheState=$cacheState",
                            "scopeNodeId=${scopeNodeId.orEmpty()}",
                            "relationCompleteness=COMPLETE",
                            "classes=${index.symbolIndex.classesByQualifiedName.size}",
                            "methods=${index.symbolIndex.methodsBySignature.size}",
                            "fields=${index.symbolIndex.fieldsByQualifiedName.size}",
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                    val projectStartedAt = System.nanoTime()
                    projector.project(index, scopeNodeId, "COMPLETE", request, cacheState).also { view ->
                        traceStage("classDiagram.completeProject", projectStartedAt) {
                            listOf(
                                "view=${request.view}",
                                "cacheState=$cacheState",
                                "scopeNodeId=${scopeNodeId.orEmpty()}",
                                "relationCompleteness=COMPLETE",
                                "visible=${LinkGraphRenderTrace.graphSummary(view.visibleGraph)}",
                                "full=${LinkGraphRenderTrace.graphSummary(view.fullGraph)}",
                                "truncated=${view.summary.truncated}",
                                "hiddenNodes=${view.summary.hiddenNodeCount}",
                                "hiddenEdges=${view.summary.hiddenEdgeCount}",
                            )
                        }
                    }.let { view ->
                        ClassDiagramViewPayload(view = view, request = request)
                    }
                }.fold(
                    onSuccess = { payload -> ClassDiagramViewResult.success(payload) },
                    onFailure = { error -> ClassDiagramViewResult.failure(error) },
                )
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                when {
                    result.cancelled || project.isDisposed -> Unit
                    result.failure != null -> {
                        logger.warn("补齐类图完整关系失败", result.failure)
                        val message = "补齐类图完整关系失败：${result.failure.message ?: result.failure.javaClass.simpleName}"
                        eventSink.emit(
                            GraphEditorApplicationEvent.IndexedGraphRequestFailed(
                                view = IndexedGraphView.CLASS_DIAGRAM,
                                requestState = AsyncRequestState.failed(
                                    message = message,
                                    requestId = requestId,
                                    scene = request.view.name,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                    result.payload?.view != null -> {
                        val message = "已补齐类图完整关系。"
                        eventSink.emit(
                            GraphEditorApplicationEvent.ClassDiagramLoaded(
                                view = result.payload.view,
                                requestState = AsyncRequestState.succeeded(
                                    requestId = requestId,
                                    scene = request.view.name,
                                    statusMessage = message,
                                    startedAtEpochMillis = startedAtEpochMillis,
                                ),
                                statusMessage = message,
                            ),
                        )
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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

private data class ClassDiagramViewPayload(
    val view: ClassDiagramResult? = null,
    val symbolIndexHint: JvmSymbolIndex? = null,
    val resolvedScopeNodeId: String? = null,
    val request: IndexedGraphRequest,
)

private data class ClassDiagramViewResult(
    val payload: ClassDiagramViewPayload? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        fun success(payload: ClassDiagramViewPayload): ClassDiagramViewResult = ClassDiagramViewResult(payload = payload)
        fun failure(error: Throwable): ClassDiagramViewResult = ClassDiagramViewResult(failure = error)
        fun cancelled(): ClassDiagramViewResult = ClassDiagramViewResult(cancelled = true)
    }
}
