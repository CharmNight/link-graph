package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.projection.business.ArchitectureGraphProjector
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

/**
 * 项目结构（架构）图工作流。
 *
 * 负责响应架构图索引请求，在后台线程上构建符号/关系索引、
 * 投影出可见架构图，并在主线程上把结果（成功/失败/取消）作为
 * 应用事件抛出，供 UI 端订阅。
 */
internal class ArchitectureGraphWorkflow(
    private val project: Project,
    private val indexSupport: ArchitectureIndexWorkflowSupport,
    private val eventSink: GraphEditorApplicationEventSink,
    private val projector: ArchitectureGraphProjector = ArchitectureGraphProjector(),
    private val logger: com.intellij.openapi.diagnostic.Logger,
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    // 自增的请求 ID，用于区分不同次架构图请求
    private val requestIds = AtomicLong()

    /**
     * 请求构建并加载架构图。
     *
     * 流程：先发出请求开始事件，随后在后台线程上构建索引并投影，
     * 若 project 已被释放则直接取消；最终通过 invokeLater 在主线程上
     * 根据 result 派发失败或加载完成事件，并对各阶段进行 trace 记录。
     */
    fun requestIndexedGraph(request: IndexedGraphRequest) {
        // 自增请求 ID 并构造运行中的状态
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
            // project 已被销毁则直接走取消分支
            val result =
                if (project.isDisposed) {
                    ArchitectureGraphViewResult.cancelled()
                } else {
                    runCatching {
                        // 是否已有完整索引缓存，用于决定本次构建的缓存命中状态
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
                        // 投影阶段开始时间，用于记录投影耗时
                        val projectStartedAt = System.nanoTime()
                        projector.project(index, request, cacheState, indexSupport.freshness()).also { view ->
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
                // 主线程回调里再次检查 project 是否已销毁
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
                    // 成功分支：发出加载完成事件
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

    /**
     * 记录某个阶段的运行 trace。
     *
     * 若未注入 runtimeTrace 则直接返回，否则使用统一 trace 工具记录阶段名、
     * 起始时间戳和详细字段，便于在调试时定位耗时与瓶颈。
     */
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

/**
 * 架构图视图构建结果。
 *
 * 内部使用：把成功/失败/取消三类结果统一收纳到一个数据类中，
 * 便于在后台线程构造、在主线程上分支处理。
 */
private data class ArchitectureGraphViewResult(
    val view: ArchitectureGraphResult? = null,
    val failure: Throwable? = null,
    val cancelled: Boolean = false,
) {
    companion object {
        /** 构造一个成功结果。 */
        fun success(view: ArchitectureGraphResult): ArchitectureGraphViewResult = ArchitectureGraphViewResult(view = view)
        /** 构造一个失败结果。 */
        fun failure(error: Throwable): ArchitectureGraphViewResult = ArchitectureGraphViewResult(failure = error)
        /** 构造一个取消结果。 */
        fun cancelled(): ArchitectureGraphViewResult = ArchitectureGraphViewResult(cancelled = true)
    }
}
